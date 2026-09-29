(ns odin.services.transaction-service-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [odin.services.transaction-service :as ts]
            [odin.services.category-service :as category-svc]
            [odin.db2 :as db2]
            [odin.services.date-service :as date]
            [clojure.java.shell :refer [sh]])
  (:import [software.amazon.awssdk.services.dynamodb DynamoDbClient]
           [software.amazon.awssdk.services.dynamodb.model
            CreateTableRequest KeySchemaElement KeyType
            AttributeDefinition ScalarAttributeType BillingMode]
           [software.amazon.awssdk.http.urlconnection UrlConnectionHttpClient]
           [software.amazon.awssdk.auth.credentials StaticCredentialsProvider AwsBasicCredentials]
           [software.amazon.awssdk.regions Region]
           [java.net URI]))

;; ---------------------------------------------------------------------------
;; Test identifiers
;; ---------------------------------------------------------------------------

(def test-user "test-user")
(def test-account "test-account")
(def test-port 8001)
(def test-container-name "odin-test-dynamodb")

;; ---------------------------------------------------------------------------
;; DynamoDB Local (Docker) lifecycle
;; ---------------------------------------------------------------------------

(defn start-test-dynamodb! []
  ;; Remove any leftover container from a previous crashed run
  (sh "docker" "rm" "-f" test-container-name)
  (println "Starting test DynamoDB Local on port" test-port "...")
  (let [result (sh "docker" "run" "-d" "--rm"
                    "-p" (str test-port ":8000")
                    "--name" test-container-name
                    "amazon/dynamodb-local")]
    (when (not= 0 (:exit result))
      (throw (ex-info "Failed to start DynamoDB Local container"
                      {:stdout (:out result) :stderr (:err result)})))))

(defn stop-test-dynamodb! []
  (println "Stopping test DynamoDB Local...")
  (sh "docker" "stop" test-container-name))

;; ---------------------------------------------------------------------------
;; AWS SDK v2 (software.amazon.awssdk) client + table helpers
;; ---------------------------------------------------------------------------

(defn build-local-client ^DynamoDbClient []
  (-> (DynamoDbClient/builder)
      (.httpClient (-> (UrlConnectionHttpClient/builder) (.build)))
      (.region (Region/of "eu-west-1"))
      (.endpointOverride (URI. (str "http://localhost:" test-port)))
      (.credentialsProvider
       (StaticCredentialsProvider/create (AwsBasicCredentials/create "local" "local")))
      (.build)))

(defn wait-for-dynamodb! [^DynamoDbClient client]
  (println "Waiting for DynamoDB Local to be ready...")
  (loop [retries 0]
    (let [ready? (try (.listTables client) true (catch Exception _ false))]
      (cond
        ready?           (println "DynamoDB Local is ready")
        (>= retries 30)  (throw (ex-info "DynamoDB Local did not become ready within 30 seconds" {}))
        :else            (do (Thread/sleep 1000) (recur (inc retries)))))))

(defn- key-schema [pk sk]
  ^java.util.Collection
  [(-> (KeySchemaElement/builder) (.attributeName pk) (.keyType KeyType/HASH)  (.build))
   (-> (KeySchemaElement/builder) (.attributeName sk) (.keyType KeyType/RANGE) (.build))])

(defn- attr-defs [pk sk]
  ^java.util.Collection
  [(-> (AttributeDefinition/builder) (.attributeName pk) (.attributeType ScalarAttributeType/S) (.build))
   (-> (AttributeDefinition/builder) (.attributeName sk) (.attributeType ScalarAttributeType/S) (.build))])

(defn create-table! [^DynamoDbClient client table-name pk sk]
  (.createTable client
                ^CreateTableRequest
                (-> (CreateTableRequest/builder)
                    (.tableName table-name)
                    (.keySchema (key-schema pk sk))
                    (.attributeDefinitions (attr-defs pk sk))
                    (.billingMode BillingMode/PAY_PER_REQUEST)
                    (.build))))

(defn create-tables! [client]
  (create-table! client db2/transaction-table-name "UserId" "Timestamp")
  (create-table! client db2/category-table-name    "UserId" "Id")
  (println "Tables created"))

;; ---------------------------------------------------------------------------
;; Table cleanup helper (reuses db2's own scan/delete path)
;; ---------------------------------------------------------------------------

(defn clear-transaction-table!
  "Delete every transaction belonging to the test user."
  []
  (doseq [item (db2/scan-table-for-user db2/transaction-table-name test-user)]
    (db2/delete-item db2/transaction-table-name
                     {:UserId (:UserId item) :Timestamp (:Timestamp item)})))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn make-date
  "Convert an ISO date string like '2025-01-27' to a unix timestamp (ms)."
  [iso-str]
  (date/localtime->unixtime (str iso-str "T00:00:00")))

(defn get-all-db-transactions
  "Read every transaction for the test user via the normal db2 path."
  []
  (db2/get-transactions-after test-user "2015-01-01" true))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn dynamodb-fixture
  "Once-per-namespace fixture: starts a fresh DynamoDB Local Docker container
   on a dedicated port, points db2/dynamodb-client at it, creates the required
   tables, and tears it all down afterwards."
  [f]
  (start-test-dynamodb!)
  (try
    (with-redefs [db2/dynamodb-client (delay (build-local-client))]
      (wait-for-dynamodb! @db2/dynamodb-client)
      (create-tables! @db2/dynamodb-client)
      (f))
    (finally
      (stop-test-dynamodb!))))

(defn clean-table-fixture
  "Per-test fixture: empties the Transaction table so each test starts clean."
  [f]
  (clear-transaction-table!)
  (f))

(use-fixtures :once dynamodb-fixture)
(use-fixtures :each clean-table-fixture)

;; ---------------------------------------------------------------------------
;; Test
;; ---------------------------------------------------------------------------

(deftest get-transactions2-updated-transactions-test
  (testing "When the bank returns transactions whose description/date changed,
            the updated versions should be stored and the old versions removed."
    (let [;; ---- dates ----
          date-jan-27 (make-date "2025-01-27")
          date-jan-28 (make-date "2025-01-28")
          date-jan-29 (make-date "2025-01-29")

          ;; ---- initial bank transactions (raw format) ----
          initial-bank-txns
          [{:amount -100 :date date-jan-27 :description "STORE PURCHASE"}
           {:amount -200 :date date-jan-27 :description "RESTAURANT VISIT"}
           {:amount -300 :date date-jan-28 :description "GAS STATION"}]

          ;; ---- second-call bank transactions ----
          ;; A: unchanged
          ;; B: description changed + date moved (Jan 27 → Jan 29)
          ;; C: description changed, same date
          ;; D: entirely new transaction
          updated-bank-txns
          [{:amount -100 :date date-jan-27 :description "STORE PURCHASE"}
           {:amount -200 :date date-jan-29 :description "RESTAURANT OSLO"}
           {:amount -300 :date date-jan-28 :description "GAS STATION UPDATED"}
           {:amount -500 :date date-jan-29 :description "NEW PURCHASE"}]

          ;; counter so retrieve-bank-transactions-from can return different
          ;; data on the first vs second invocation
          bank-call-count (atom 0)]

      (with-redefs [;; year-by-year is only called on the very first call (empty DB)
                    ts/retrieve-all-transactions-year-by-year
                    (fn [_token _account-key]
                      initial-bank-txns)

                    ;; called during the merge step of every call
                    ts/retrieve-bank-transactions-from
                    (fn [_date _token _account-key]
                      (let [n (swap! bank-call-count inc)]
                        (if (= n 1)
                          initial-bank-txns      ; 1st call – same as initial
                          updated-bank-txns)))    ; 2nd call – with updates

                    ;; no categories needed for this test
                    category-svc/get-categories-with-filters
                    (fn [_user-id] [])

                    ;; no loan histories to extend
                    db2/get-loans
                    (fn [_user-id] [])]

        ;; ============================================================
        ;; FIRST CALL – initial load
        ;; ============================================================
        (ts/get-transactions2 test-user test-account {:access_token "test"} "test-key")

        (let [db-txns (get-all-db-transactions)]
          (is (= 3 (count db-txns))
              "After the first call the DB should contain exactly 3 transactions")

          (is (= #{"STORE PURCHASE" "RESTAURANT VISIT" "GAS STATION"}
                 (set (map :description db-txns)))
              "All original descriptions should be present"))

        ;; ============================================================
        ;; SECOND CALL – bank returns updated descriptions / dates
        ;; ============================================================
        (ts/get-transactions2 test-user test-account {:access_token "test"} "test-key")

        (let [db-txns      (get-all-db-transactions)
              descriptions (set (map :description db-txns))]

          ;; There should be exactly 4 transactions:
          ;;   A (unchanged) + B' (updated) + C' (updated) + D (new)
          (is (= 4 (count db-txns))
              "After the second call the DB should contain exactly 4 transactions
               (old B and C removed, updated B' and C' added, plus new D)")

          ;; Updated descriptions must be present
          (is (contains? descriptions "STORE PURCHASE")
              "Unchanged transaction A should still be in DB")
          (is (contains? descriptions "RESTAURANT OSLO")
              "Updated description for B should be in DB")
          (is (contains? descriptions "GAS STATION UPDATED")
              "Updated description for C should be in DB")
          (is (contains? descriptions "NEW PURCHASE")
              "New transaction D should be in DB")

          ;; Old descriptions must be gone
          (is (not (contains? descriptions "RESTAURANT VISIT"))
              "Old description for B should NOT be in DB")
          (is (not (contains? descriptions "GAS STATION"))
              "Old description for C should NOT be in DB"))))))
