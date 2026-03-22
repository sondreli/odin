(ns odin.db2
  (:require [cognitect.aws.client.api :as aws]
            [clojure.data.json :as json]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [odin.services.config-service :as config]
            [odin.services.date-service :as date]
            ))

(defn pr-edn-str [& xs]
  (binding [*print-length* nil
            *print-dup* nil
            *print-level* nil
            *print-readably* true]
    (apply pr-str xs)))

(def dynamodb-client
  (if config/dynamodb-endpoint
    (let [uri (java.net.URI. config/dynamodb-endpoint)]
      (aws/client {:api :dynamodb
                   :endpoint-override {:protocol (keyword (.getScheme uri))
                                       :hostname (.getHost uri)
                                       :port (.getPort uri)}}))
    (aws/client {:api :dynamodb})))

(defn write-item
  "Writes an item to the specified DynamoDB table.

  Args:
  - table-name: String, the name of the DynamoDB table.
  - item: Map, where keys are attribute names and values are attribute values formatted
          for DynamoDB (e.g., {:S \"string\"}, {:N \"number\"})."
  [table-name item]
  (let [request {:TableName table-name
                 :Item item}
        response (aws/invoke dynamodb-client {:op :PutItem :request request})]
    ;; Return the response or handle errors as needed
    response))

(def transactions [
  {:user-id "xxx" :date 1733353200000, :amount -4568, :description "RUTERAPPEN", :db-id 79164837202750, :index 3, :levenshtein 0}
  {:user-id "xxx" :date 1733439600000, :description "RUTERAPPEN", :db-id 79164837202755, :index 6, :levenshtein 0}
  {:user-id "xxx" :date 1733439600000, :description "RUTERAPPEN", :db-id 79164837202754, :index 5, :levenshtein 0}
  {:user-id "xxx" :date 1733439600000, :description "MENY HOLMLIA HOLMLIA SENT OSLO", :db-id 79164837202756, :index 0, :levenshtein 0}
  {:user-id "xxx" :date 1733698800000, :description "Vipps*Bilkraft Elbil-ladi", :db-id 79164837202765, :index 0, :levenshtein 0}
])

(def db-transactions [{:UserId {:S "xxx"}, :Timestamp {:N "1733353200000"}, :Description {:S "RUTERAPPEN"}, :Amount {:N "-4568"}}
                      {:UserId {:S "xxx"}, :Timestamp {:N "1733439600000"}, :Description {:S "RUTERAPPEN"}, :Amount {:N "0"}}
                      {:UserId {:S "xxx"}, :Timestamp {:N "1733439600001"}, :Description {:S "RUTERAPPEN"}, :Amount {:N "0"}}])

(defn pad-left
  "Pads a string with a character to the specified length."
  [input-str length pad-char]
  (let [s (str input-str)
        fmt (str "%" length "s")  ; e.g., "%16s" for length 16
        padded (format fmt s)]    ; Pads with spaces
    (s/replace-first padded #"^ *" (s/join (repeat (- length (count s)) pad-char)))))

; what am I trying to do? Input all data
; nil -> should not be added
; required: user-id, date, amount
; default value:
;              Name                   DB-name             Type  Required Alter value
(def transaction-config [[:user-id              :UserId              :S    true  identity]
                         [:date                 :Timestamp           :S    true  +]
                         [:description          :Description         :S    false identity]
                         [:amount               :Amount              :N    true  identity]
                         [:category-id          :CategoryId          :S    false identity]
                         [:marked-by-filter?    :MarkedByFilter      :BOOL false identity]
                         [:source               :Source              :S    false identity]
                         [:tag-ids              :TagIds              :S    false identity]
                         [:filter-tag-ids       :FilterTagIds        :S    false identity]])

(def category-config [[:user-id     :UserId     :S]
                      [:id          :Id         :S]
                      [:name        :Name       :S]
                      [:color       :Color      :S]
                      [:color-value :ColorValue :S]
                      [:marker      :Marker     :S]
                      [:target      :Target     :N]
                      [:bucket      :Bucket     :S]])

(def tag-config [[:user-id  :UserId  :S]
                 [:id       :Id      :S]
                 [:name     :Name    :S]
                 [:color    :Color   :S]])

(def filter-config [[:user-id      :UserId      :S]
                    [:id           :Id          :S]
                    [:category-id  :CategoryId  :S]
                    [:text         :Text        :S]
                    [:order-index  :OrderIndex  :N]
                    [:tag-ids      :TagIds      :S]])

(def report-config [[:user-id      :UserId      :S]
                    [:id           :Id          :S]
                    [:name         :Name        :S]
                    [:expression   :Expression  :S]
                    [:chart-type   :ChartType   :S]
                    [:period-start :PeriodStart :N]
                    [:period-end   :PeriodEnd   :N]
                    [:period-type  :PeriodType  :S]])

(def transaction-table-name "Transaction")
(def category-table-name "Category")
(def report-table-name "Report")
(def tag-table-name "Tag")
(def filter-table-name "Filter")
(def user-table-name "User")
(def account-table-name "Account")

;;
;;  Write to database
;;
(defn write-batch-items ; only 25 items in each request
  "Writes multiple items to the specified DynamoDB table in batch mode.
  Args:
  - table-name: String, the name of the DynamoDB table.
  - items: List of Maps, where each map represents an item in DynamoDB format."
  [table-name items]
  ;; (println items)
  (let [
        request {:RequestItems {table-name
                                (mapv (fn [item] {:PutRequest {:Item item}}) items)}}
        ;; _ (println (json/write-str request))
        response (aws/invoke dynamodb-client {:op :BatchWriteItem :request request})]
    response))

(defn write-items-to-db [table-name items]
  ;; (println "Items to write:" items)
  (let [result (write-batch-items table-name items)]
    ;; (println "Batch Write Result:" result)
    (if (and (contains? result :cognitect.aws.http/status)
             (-> result :cognitect.aws.http/status (>= 400)))
      (do
        (println "Error writing to DynamoDB:" result)
        (when (= (:cognitect.anomalies/category result) :cognitect.anomalies/incorrect)
          (println "ValidationException occurred. Problematic items:" items))
        result)
      (do
        (when (contains? result :UnprocessedItems)
          (println "Warning: Some items were not processed:" (:UnprocessedItems result))
          ;;(println "All items written successfully")
          )
        result))))

(defn store-items [table-name items]
  (let [item-groups (partition 25 25 nil items)]
    (doall (map #(write-items-to-db table-name %) item-groups))))

(defn date->sortkey [date date-index]
  (str (pad-left date 16 "0") "#" (pad-left date-index 4 "0")))

(defn add-key-val-from [transaction m [key db-key db-type]]
  (if-let [value (get transaction key)]
    (let [db-val (cond
                   (= key :date) (date->sortkey value (:date-index transaction))
                   (sequential? value) (pr-edn-str value)
                   :else (str value))
          new-value {db-type db-val}]
      (assoc m db-key new-value))
    m))

(defn item->db-item [config item]
  (reduce (partial add-key-val-from item) {} config))

(defn transactions->db-transactions [transactions]
  (map #(item->db-item transaction-config %) transactions))

(defn store-transactions [transactions]
  (let [db-transactions (transactions->db-transactions transactions)
        _ (println "Converted to db-transactions count:" (count db-transactions))
        _ (println "Sample db-transaction:" (first db-transactions))
        ;db-transaction-groups (partition 25 25 nil db-transactions)
        ]
    ;; (doall (map #(write-transactions-to-db transaction-table-name %) db-transaction-groups))
    (let [store-result (store-items transaction-table-name db-transactions)]
    transactions)))
;;
;;  Store category
;;

(defn store-category [category]
  ; do pre transformation of marker?
  (let [db-category (item->db-item category-config category)]
    (store-items category-table-name [db-category])))

;;
;; Update transactions
;;

;; (defn update-transactions [transactions]
;;   (let [; map to db types
;;         db-transactions (map #(item->db-item transaction-config %) transactions)
;;         _ (store-items transaction-table-name db-transactions)
;;         ]))


;;
;; Delete transactions
;;
(defn delete-transactions
  "Deletes multiple items from a DynamoDB table using BatchWriteItem.
   - table-name: String name of the DynamoDB table.
   - items: List of maps, each containing :pk (partition key, string) and :sk (sort key, string).
   Handles batching in groups of 25 and retries unprocessed items."
  [transactions]
  (let [max-batch-size 25
        table-name transaction-table-name
        prepare-request (fn [[pk sk]]
                          {:DeleteRequest
                           {:Key {:UserId    pk
                                  :Timestamp sk}}})
        partition-and-sort-keys (->> transactions 
                                     transactions->db-transactions
                                     (map #(vector (:UserId %) (:Timestamp %))))
        requests (map prepare-request partition-and-sort-keys)]

    ;; Function to process a batch and handle unprocessed items recursively
    (letfn [(process-batch [batch-request]
              (let [response (aws/invoke dynamodb-client {:op :BatchWriteItem :request batch-request})
                    unprocessed (get-in response [:UnprocessedItems table-name])]
                (when (seq unprocessed)
                  (process-batch {:RequestItems {table-name unprocessed}}))))]

      ;; Partition requests into batches and process each
      (doseq [group (partition-all max-batch-size requests)]
        (process-batch {:RequestItems {table-name group}}))))
  ;; Return nil or a success message if needed
  nil)

;;
;;  Read from database
;;
(defn read-batch-items
  "Reads multiple items from the specified DynamoDB table using their primary keys.

  Args:
  - table-name: String, the name of the DynamoDB table.
  - keys: List of Maps, where each map represents the primary key of an item."
  [table-name keys]
  (let [request {:RequestItems {table-name {:Keys keys}}}
                                _ (println "read-batch-items: " request)
        response (aws/invoke dynamodb-client {:op :BatchGetItem :request request})]
        (println response)
    response))

(defn query-all-items
  [table-name partition-key partition-value]
  (let [; Construct the query parameters
        query-params {:TableName table-name
                      :KeyConditionExpression "#pk = :v"
                      :ExpressionAttributeNames {"#pk" partition-key}
                      :ExpressionAttributeValues {":v" partition-value}}]

    (try
      (let [result (aws/invoke dynamodb-client {:op :Query :request query-params})
            _ (println "query-all-items: " result)
            items (:Items result)]
        ;(map #(get % attribute-name) items)
        items)
      (catch Exception e
        (println "An error occurred while querying DynamoDB:" (.getMessage e))
        nil))))

(defn query-items-greater-than
  [table-name partition-key-name partition-key-value sort-key-name sort-key-value]
  (println sort-key-value)
  (let [request {:TableName table-name
                 :KeyConditionExpression "#pk = :partitionval AND #sk > :sortval"
                 :ExpressionAttributeValues {":partitionval" partition-key-value
                                             ":sortval" sort-key-value}
                 :ExpressionAttributeNames {"#pk" partition-key-name
                                            "#sk" sort-key-name}}
        _ (println "query-items-greater-than: " request)
        response (aws/invoke dynamodb-client {:op :Query :request request})]
    (:Items response)))

(defn query-items-greater-than2
  "Queries DynamoDB with pagination to retrieve all items matching the condition.
   - table-name: DynamoDB table name.
   - partition-key-name: Name of the partition key attribute.
   - sort-key-name: Name of the sort key attribute.
   - partition-key-value: Value of the partition key.
   - sort-key-value: Sort key value for the > condition (e.g., timestamp or formatted sort key)."
  [table-name partition-key-name partition-key-value sort-key-name sort-key-value attributes retrieve-all?]
  (let [attr-names (into {} (map (fn [attr] [(str "#attr_" attr) attr]) attributes))
        projection-expr (s/join ", " (keys attr-names))]
    (loop [items []
           last-key nil
           iteration 0]
      (let [start-time (System/currentTimeMillis)
            query-params (merge
                          {:TableName table-name
                           :KeyConditionExpression "#pk = :partitionval AND #sk > :sortval"
                           :ExpressionAttributeNames (merge {"#pk" partition-key-name
                                                             "#sk" sort-key-name}
                                                            attr-names)
                           :ExpressionAttributeValues {":partitionval" {:S partition-key-value}
                                                       ":sortval" {:S sort-key-value}}
                           :ProjectionExpression projection-expr}
                          (when last-key
                            {:ExclusiveStartKey last-key}))
            ;; _ (println "query-items-greater-than2 query-params: " query-params)
          ;response (ddb/query query-params)
            response (aws/invoke dynamodb-client {:op :Query :request query-params})
            new-items (concat items (:Items response))
            elapsed-time (- (System/currentTimeMillis) start-time)]
        (println "Query iteration" iteration "- Retrieved" (count (:Items response)) "items in" elapsed-time "ms")
        ;; (println "query-items-greater-than2: " response)
        ;; (when (:LastEvaluatedKey response)
        ;;   (println "query-items-greater-than2 last-evaluated-key: " (:LastEvaluatedKey response)))
        (if (and (:LastEvaluatedKey response) retrieve-all?)
          (recur new-items (:LastEvaluatedKey response) (inc iteration))
          (do
            (println "Query completed - Total items:" (count new-items) "in" (inc iteration) "iterations")
            new-items))))))

(defn translate-key-val [m [key db-key db-type _]]
  (if-let [db-val (get m db-key)]
    (let [val-trans-fx (case db-type
                         :S identity
                         :N #(Double. %)
                         identity)
        value (-> m (get db-key) (get db-type) val-trans-fx)]
    (-> m
        (dissoc db-key)
        (assoc key value)))
    m))

(defn split-composite-sortkey-date [transaction]
  (let [[date date-index] (-> transaction
                              :date
                              (s/split #"#"))]
    (-> transaction
        (assoc :date (Long/parseLong date))
        (assoc :date-index (Long/parseLong date-index)))))

(defn- parse-edn-field [transaction field]
  (let [v (get transaction field)]
    (if (and v (string? v) (not= v ""))
      (assoc transaction field (edn/read-string v))
      transaction)))

(defn db-transactions->transactions [db-transactions]
  (->> db-transactions
       (map #(reduce translate-key-val % transaction-config))
       (map split-composite-sortkey-date)
       (map #(-> % (parse-edn-field :tag-ids) (parse-edn-field :filter-tag-ids)))))

;; (db-transactions->transactions db-transactions)

(defn iso-date->sortkey [iso-date]
  (-> iso-date
      (str "T00:00:00")
      (date/localtime->unixtime)
      (pad-left 16 "0")
      (str "#0000")))

(defn get-transactions-after [user-id iso-date retrieve-all?]
  (println "=== get-transactions-after ===")
  (println "user-id:" user-id ", iso-date:" iso-date ", retrieve-all?:" retrieve-all?)
  (let [table-name transaction-table-name
        db-transactions (query-items-greater-than2 table-name
                                           "UserId" user-id
                                           "Timestamp" (iso-date->sortkey iso-date)
                                                   ["Timestamp" "Description" "Amount" "CategoryId" "MarkedByFilter" "TagIds" "FilterTagIds"]
                                                   retrieve-all?)
        _ (println "Retrieved" (count db-transactions) "db-transactions from DynamoDB")
        _ (println "db-transactions one: " (first db-transactions))
        transactions (->> db-transactions
                          (sort-by #(-> % :Timestamp :S))
                          db-transactions->transactions
                          (map #(assoc % :user-id user-id)))
        _ (println "Converted to" (count transactions) "Clojure transactions")
        _ (println "Sample Clojure transaction:" (first transactions))
        _ (println "=== END get-transactions-after ===")]
    transactions))

(defn translate-marker [category]
  (let [marker (-> category :marker edn/read-string)]
    (assoc category :marker marker)))

(defn db-categories->categories [db-categories]
  (->> db-categories
       (map #(reduce translate-key-val % category-config))
       (map translate-marker)))

(defn get-categories [user-id]
  (let [db-categories (query-all-items category-table-name "UserId" {:S user-id})
        categories (db-categories->categories db-categories)]
    categories))

;;
;; Delete entry
;;

; hmm, should also delete the category from all transactsions
; update the transactions in frontend, then pass all the updates to store-transactions
(defn delete-category [user-id category-id]
  (aws/invoke dynamodb-client
              {:op :DeleteItem
               :request {:TableName category-table-name
                         :Key {:UserId {:S user-id}
                               :Id {:S category-id}}}}))

;;
;; Reports
;;

(defn store-report [report]
  (let [db-report (item->db-item report-config report)]
    (store-items report-table-name [db-report])))

(defn get-reports [user-id]
  (let [db-reports (query-all-items report-table-name "UserId" {:S user-id})]
    (->> db-reports
         (map #(reduce translate-key-val % report-config)))))

(defn delete-report [user-id report-id]
  (aws/invoke dynamodb-client
              {:op :DeleteItem
               :request {:TableName report-table-name
                         :Key {:UserId {:S user-id}
                               :Id {:S report-id}}}}))

;;
;; Tags
;;

(defn store-tag [tag]
  (let [db-tag (item->db-item tag-config tag)]
    (store-items tag-table-name [db-tag])))

(defn get-tags [user-id]
  (let [db-tags (query-all-items tag-table-name "UserId" {:S user-id})]
    (->> db-tags
         (map #(reduce translate-key-val % tag-config)))))

(defn delete-tag [user-id tag-id]
  (aws/invoke dynamodb-client
              {:op :DeleteItem
               :request {:TableName tag-table-name
                         :Key {:UserId {:S user-id}
                               :Id {:S tag-id}}}}))

;;
;; Filters
;;

(defn translate-filter-tag-ids [filter-item]
  (let [tag-ids-str (:tag-ids filter-item)]
    (if (and tag-ids-str (not= tag-ids-str ""))
      (assoc filter-item :tag-ids (edn/read-string tag-ids-str))
      (assoc filter-item :tag-ids []))))

(defn store-filter [filter-item]
  (let [prepared (if (sequential? (:tag-ids filter-item))
                   (assoc filter-item :tag-ids (pr-edn-str (:tag-ids filter-item)))
                   filter-item)
        db-filter (item->db-item filter-config prepared)]
    (store-items filter-table-name [db-filter])))

(defn store-filters [filters]
  (let [db-filters (map (fn [f]
                          (let [prepared (if (sequential? (:tag-ids f))
                                          (assoc f :tag-ids (pr-edn-str (:tag-ids f)))
                                          f)]
                            (item->db-item filter-config prepared)))
                        filters)]
    (store-items filter-table-name db-filters)))

(defn get-filters [user-id]
  (let [db-filters (query-all-items filter-table-name "UserId" {:S user-id})]
    (->> db-filters
         (map #(reduce translate-key-val % filter-config))
         (map translate-filter-tag-ids))))

(defn delete-filter [user-id filter-id]
  (aws/invoke dynamodb-client
              {:op :DeleteItem
               :request {:TableName filter-table-name
                         :Key {:UserId {:S user-id}
                               :Id {:S filter-id}}}}))

(defn delete-filters-by-category [user-id category-id]
  (let [all-filters (get-filters user-id)
        category-filters (filter #(= (:category-id %) category-id) all-filters)]
    (doseq [f category-filters]
      (delete-filter user-id (:id f)))))

;; Function to list items from a DynamoDB table
(defn list-items [table-name]
  (let [request {:TableName table-name}
        response (aws/invoke dynamodb-client {:op :Scan :request request})]
    (map #(into {} %) (:Items response))))

;;
;; Users
;;

(defn put-user [{:keys [user-id email password-hash created-at]}]
  (write-item user-table-name
              {:UserId       {:S user-id}
               :Email        {:S email}
               :PasswordHash {:S password-hash}
               :CreatedAt    {:S created-at}}))

(defn get-user-by-id [user-id]
  (let [response (aws/invoke dynamodb-client
                             {:op :GetItem
                              :request {:TableName user-table-name
                                        :Key {:UserId {:S user-id}}}})]
    (when-let [item (:Item response)]
      {:user-id       (get-in item [:UserId :S])
       :email         (get-in item [:Email :S])
       :password-hash (get-in item [:PasswordHash :S])
       :created-at    (get-in item [:CreatedAt :S])})))

(defn get-user-by-email [email]
  (let [response (aws/invoke dynamodb-client
                             {:op :Query
                              :request {:TableName user-table-name
                                        :IndexName "EmailIndex"
                                        :KeyConditionExpression "#e = :email"
                                        :ExpressionAttributeNames {"#e" "Email"}
                                        :ExpressionAttributeValues {":email" {:S email}}}})]
    (when-let [item (first (:Items response))]
      {:user-id       (get-in item [:UserId :S])
       :email         (get-in item [:Email :S])
       :password-hash (get-in item [:PasswordHash :S])
       :created-at    (get-in item [:CreatedAt :S])})))

;;
;; Accounts (bank connections)
;;

(defn put-account [{:keys [user-id account-id provider account-name
                           token-data bank-account-key created-at]}]
  (write-item account-table-name
              (cond-> {:UserId    {:S user-id}
                       :AccountId {:S account-id}
                       :Provider  {:S provider}
                       :CreatedAt {:S created-at}}
                account-name     (assoc :AccountName    {:S account-name})
                token-data       (assoc :TokenData      {:S token-data})
                bank-account-key (assoc :BankAccountKey  {:S bank-account-key}))))

(defn get-accounts-for-user [user-id]
  (let [items (:Items (aws/invoke dynamodb-client
                                  {:op :Query
                                   :request {:TableName account-table-name
                                             :KeyConditionExpression "#uid = :uid"
                                             :ExpressionAttributeNames {"#uid" "UserId"}
                                             :ExpressionAttributeValues {":uid" {:S user-id}}}}))]
    (mapv (fn [item]
            (cond-> {:user-id    (get-in item [:UserId :S])
                     :account-id (get-in item [:AccountId :S])
                     :provider   (get-in item [:Provider :S])
                     :created-at (get-in item [:CreatedAt :S])}
              (get item :AccountName)    (assoc :account-name    (get-in item [:AccountName :S]))
              (get item :TokenData)      (assoc :token-data      (get-in item [:TokenData :S]))
              (get item :BankAccountKey) (assoc :bank-account-key (get-in item [:BankAccountKey :S]))))
          items)))

(defn get-account [user-id account-id]
  (let [response (aws/invoke dynamodb-client
                             {:op :GetItem
                              :request {:TableName account-table-name
                                        :Key {:UserId    {:S user-id}
                                              :AccountId {:S account-id}}}})]
    (when-let [item (:Item response)]
      (cond-> {:user-id    (get-in item [:UserId :S])
               :account-id (get-in item [:AccountId :S])
               :provider   (get-in item [:Provider :S])
               :created-at (get-in item [:CreatedAt :S])}
        (get item :AccountName)    (assoc :account-name    (get-in item [:AccountName :S]))
        (get item :TokenData)      (assoc :token-data      (get-in item [:TokenData :S]))
        (get item :BankAccountKey) (assoc :bank-account-key (get-in item [:BankAccountKey :S]))))))

(defn update-account-tokens [user-id account-id token-data-json]
  (aws/invoke dynamodb-client
              {:op :UpdateItem
               :request {:TableName account-table-name
                         :Key {:UserId    {:S user-id}
                               :AccountId {:S account-id}}
                         :UpdateExpression "SET TokenData = :td"
                         :ExpressionAttributeValues {":td" {:S token-data-json}}}}))

(defn update-account-bank-key [user-id account-id bank-account-key]
  (aws/invoke dynamodb-client
              {:op :UpdateItem
               :request {:TableName account-table-name
                         :Key {:UserId    {:S user-id}
                               :AccountId {:S account-id}}
                         :UpdateExpression "SET BankAccountKey = :bk"
                         :ExpressionAttributeValues {":bk" {:S bank-account-key}}}}))

(defn delete-account [user-id account-id]
  (aws/invoke dynamodb-client
              {:op :DeleteItem
               :request {:TableName account-table-name
                         :Key {:UserId    {:S user-id}
                               :AccountId {:S account-id}}}}))

;;
;; Scan table (used for migration)
;;

(defn scan-table-for-user [table-name user-id]
  (loop [items [] last-key nil]
    (let [request (cond-> {:TableName table-name
                           :FilterExpression "UserId = :uid"
                           :ExpressionAttributeValues {":uid" {:S user-id}}}
                    last-key (assoc :ExclusiveStartKey last-key))
          response (aws/invoke dynamodb-client {:op :Scan :request request})
          all-items (concat items (:Items response))]
      (if (:LastEvaluatedKey response)
        (recur all-items (:LastEvaluatedKey response))
        (vec all-items)))))

(defn- delete-all-items-for-user
  "Delete all items in a table for a given user. pk-attr and sk-attr are the
   keyword attribute names for the partition and sort keys."
  [table-name pk-attr sk-attr user-id]
  (let [items (scan-table-for-user table-name user-id)]
    (doseq [item items]
      (let [key (cond-> {pk-attr (get item pk-attr)}
                  sk-attr (assoc sk-attr (get item sk-attr)))]
        (aws/invoke dynamodb-client
                    {:op :DeleteItem
                     :request {:TableName table-name :Key key}})))
    (count items)))

(defn delete-all-user-data
  "Delete all data belonging to a user across all tables, then delete the user record."
  [user-id]
  (let [counts (atom {})]
    (swap! counts assoc "Transaction" (delete-all-items-for-user transaction-table-name :UserId :Timestamp user-id))
    (swap! counts assoc "Category" (delete-all-items-for-user category-table-name :UserId :Id user-id))
    (swap! counts assoc "Report" (delete-all-items-for-user report-table-name :UserId :Id user-id))
    (swap! counts assoc "Tag" (delete-all-items-for-user tag-table-name :UserId :Id user-id))
    (swap! counts assoc "Filter" (delete-all-items-for-user filter-table-name :UserId :Id user-id))
    (swap! counts assoc "Account" (delete-all-items-for-user account-table-name :UserId :AccountId user-id))
    (aws/invoke dynamodb-client
                {:op :DeleteItem
                 :request {:TableName user-table-name
                           :Key {:UserId {:S user-id}}}})
    (swap! counts assoc "User" 1)
    @counts))

(defn -main [& args]
  (println "db2 main"))
