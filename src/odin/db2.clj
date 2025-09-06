(ns odin.db2
  (:require [cognitect.aws.client.api :as aws]
            [clojure.data.json :as json]
            [clojure.string :as s]
            [clojure.edn :as edn]
            ;[odin.services.transaction-service :as transaction]
            [odin.services.date-service :as date]
            ))

(defn pr-edn-str [& xs]
  (binding [*print-length* nil
            *print-dup* nil
            *print-level* nil
            *print-readably* true]
    (apply pr-str xs)))

;; Define the client for the local DynamoDB instance
(def dynamodb-client
  (aws/client {:api :dynamodb
               :endpoint-override {:protocol :http
                                   :hostname "localhost"
                                   :port 8000}}))
                                  
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
                        ;;  [:manually-categorized :ManuallyCategorized :BOOL false #(if (nil? %) false %)]
                         [:marked-by-filter?    :MarkedByFilter      :BOOL false identity]
                         [:source               :Source              :S    false identity]])
                        
(def category-config [[:user-id     :UserId     :S]
                      [:id          :Id         :S]
                      [:name        :Name       :S]
                      [:color       :Color      :S]
                      [:color-value :ColorValue :S]
                      [:marker      :Marker     :S]])

(def transaction-table-name "Transaction")
(def category-table-name "Category")

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

;; (defn write-items-to-db [table-name items]
;;   (try
;;     (let [result (write-batch-items table-name items)]
;;       ;; (println "Batch Write Result:" result)
;;       (if (contains? result :UnprocessedItems)
;;         (println "Warning: Some items were not processed:" (:UnprocessedItems result))
;;         (println "All items processed successfully")))
;;     (catch Exception e
;;       (println "Failed to write batch items:" (.getMessage e)))))
(defn write-items-to-db [table-name items]
    (let [result (write-batch-items table-name items)]
      ;; (println "Batch Write Result:" result)
      (if (contains? result :UnprocessedItems)
        (println "Warning: Some items were not processed:" (:UnprocessedItems result))
        (println "All items written successfully"))))
  
(defn store-items [table-name items]
  (let [item-groups (partition 25 25 nil items)]
    (doall (map #(write-items-to-db table-name %) item-groups))))

(defn date->sortkey [date date-index]
  (str (pad-left date 16 "0") "#" (pad-left date-index 4 "0")))

(defn add-key-val-from [transaction m [key db-key db-type]] ; might be nil, 
  (if-let [value (get transaction key)]
    (let [db-val (cond
               (= key :date) (date->sortkey value (:date-index transaction))
               :else (str value))
          new-value {db-type db-val}
          ]
    (assoc m db-key new-value))
    m))
  
(defn item->db-item [config item]
  (reduce (partial add-key-val-from item) {} config))

(defn transactions->db-transactions [transactions]
  (map #(item->db-item transaction-config %) transactions))

(defn store-transactions [transactions]
  (println "store-transactions " (count transactions))
  (let [db-transactions (transactions->db-transactions transactions)
        ;; _ (println "db-transactions: " db-transactions)
        ;db-transaction-groups (partition 25 25 nil db-transactions)
        ]
    ;; (doall (map #(write-transactions-to-db transaction-table-name %) db-transaction-groups))
    (store-items transaction-table-name db-transactions)
    transactions))
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

(defn db-transactions->transactions [db-transactions]
  (->> db-transactions
       (map #(reduce translate-key-val % transaction-config))
       (map split-composite-sortkey-date)))

;; (db-transactions->transactions db-transactions)

(defn iso-date->sortkey [iso-date]
  (-> iso-date
      (str "T00:00:00")
      (date/localtime->unixtime)
      (pad-left 16 "0")
      (str "#0000")))

(defn get-transactions-after [iso-date retrieve-all?]
  (let [table-name transaction-table-name 
        db-transactions (query-items-greater-than2 table-name
                                           "UserId" "xxx"
                                           "Timestamp" (iso-date->sortkey iso-date)
                                                   ["Timestamp" "Description" "Amount" "CategoryId" "MarkedByFilter"]
                                                   retrieve-all?)
        _ (println "db-transactions one: " (first db-transactions))
        transactions (->> db-transactions (sort-by #(-> % :Timestamp :S)) db-transactions->transactions)]
    transactions))

(defn translate-marker [category]
  (let [marker (-> category :marker edn/read-string)]
    (assoc category :marker marker)))

(defn db-categories->categories [db-categories]
  (->> db-categories
       (map #(reduce translate-key-val % category-config))
       (map translate-marker)))

(defn get-categories []
  (let [db-categories (query-all-items category-table-name "UserId" {:S "xxx"})
        categories (db-categories->categories db-categories)]
    categories))

(get-transactions-after "2024-10-01" false)

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

;; Function to list items from a DynamoDB table
(defn list-items [table-name]
  (let [request {:TableName table-name}
        response (aws/invoke dynamodb-client {:op :Scan :request request})]
    (map #(into {} %) (:Items response))))

(defn -main [& args]
  (let [table-name "YourTableName"
        ;; items (list-items table-name)
        ]
    ;; (store-transactions transactions)
    (println (get-transactions-after "2024-10-01" false))
    ))
