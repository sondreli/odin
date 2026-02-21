(ns odin.services.transaction-service-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [odin.services.transaction-service :as ts]
            [odin.db2 :as db2]
            [odin.services.date-service :as date]
            [cognitect.aws.client.api :as aws]
            [clojure.java.shell :refer [sh]]))

;; ---------------------------------------------------------------------------
;; DynamoDB Local (Docker) lifecycle
;; ---------------------------------------------------------------------------

(def test-port 8001)
(def test-container-name "odin-test-dynamodb")

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

(defn create-test-client []
  (aws/client {:api :dynamodb
               :endpoint-override {:protocol :http
                                   :hostname "localhost"
                                   :port test-port}}))

(defn wait-for-dynamodb! [client]
  (println "Waiting for DynamoDB Local to be ready...")
  (loop [retries 0]
    (let [result (try
                   (aws/invoke client {:op :ListTables :request {}})
                   (catch Exception _ nil))]
      (cond
        (and (map? result) (contains? result :TableNames))
        (println "DynamoDB Local is ready")

        (>= retries 30)
        (throw (ex-info "DynamoDB Local did not become ready within 30 seconds" {}))

        :else
        (do (Thread/sleep 1000)
            (recur (inc retries)))))))

(defn create-tables! [client]
  (aws/invoke client
              {:op :CreateTable
               :request {:TableName "Transaction"
                         :KeySchema [{:AttributeName "UserId"    :KeyType "HASH"}
                                     {:AttributeName "Timestamp" :KeyType "RANGE"}]
                         :AttributeDefinitions
                         [{:AttributeName "UserId"    :AttributeType "S"}
                          {:AttributeName "Timestamp" :AttributeType "S"}]
                         :BillingMode "PAY_PER_REQUEST"}})
  (aws/invoke client
              {:op :CreateTable
               :request {:TableName "Category"
                         :KeySchema [{:AttributeName "UserId" :KeyType "HASH"}
                                     {:AttributeName "Id"     :KeyType "RANGE"}]
                         :AttributeDefinitions
                         [{:AttributeName "UserId" :AttributeType "S"}
                          {:AttributeName "Id"     :AttributeType "S"}]
                         :BillingMode "PAY_PER_REQUEST"}})
  (println "Tables created"))

;; ---------------------------------------------------------------------------
;; Table cleanup helper
;; ---------------------------------------------------------------------------

(defn clear-transaction-table!
  "Delete every item in the Transaction table.
   Uses a Scan to discover keys, then BatchWriteItem to remove them."
  []
  (let [response (aws/invoke db2/dynamodb-client
                              {:op :Scan
                               :request {:TableName "Transaction"
                                         :ProjectionExpression "UserId, #ts"
                                         :ExpressionAttributeNames {"#ts" "Timestamp"}}})
        items (:Items response)]
    (when (seq items)
      (doseq [batch (partition-all 25 items)]
        (let [delete-requests (mapv (fn [item]
                                      {:DeleteRequest
                                       {:Key {"UserId"    (:UserId item)
                                              "Timestamp" (:Timestamp item)}}})
                                    batch)]
          (aws/invoke db2/dynamodb-client
                      {:op :BatchWriteItem
                       :request {:RequestItems {"Transaction" delete-requests}}}))))))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn make-date
  "Convert an ISO date string like '2025-01-27' to a unix timestamp (ms)."
  [iso-str]
  (date/localtime->unixtime (str iso-str "T00:00:00")))

(defn get-all-db-transactions
  "Read every transaction from the Transaction table via the normal db2 path."
  []
  (db2/get-transactions-after "2015-01-01" true))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn dynamodb-fixture
  "Once-per-namespace fixture: starts a fresh DynamoDB Local Docker container
   on a dedicated port, creates the required tables, swaps db2/dynamodb-client
   to point at it, and tears it all down afterwards."
  [f]
  (start-test-dynamodb!)
  (let [test-client (create-test-client)]
    (try
      (wait-for-dynamodb! test-client)
      (create-tables! test-client)
      (with-redefs [db2/dynamodb-client test-client]
        (f))
      (finally
        (stop-test-dynamodb!)))))

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
                    db2/get-categories
                    (fn [] [])]

        ;; ============================================================
        ;; FIRST CALL – initial load
        ;; ============================================================
        (ts/get-transactions2 {:access_token "test"} "test-key")

        (let [db-txns (get-all-db-transactions)]
          (is (= 3 (count db-txns))
              "After the first call the DB should contain exactly 3 transactions")

          (is (= #{"STORE PURCHASE" "RESTAURANT VISIT" "GAS STATION"}
                 (set (map :description db-txns)))
              "All original descriptions should be present"))

        ;; ============================================================
        ;; SECOND CALL – bank returns updated descriptions / dates
        ;; ============================================================
        (ts/get-transactions2 {:access_token "test"} "test-key")

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
