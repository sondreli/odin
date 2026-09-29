(ns odin.db2
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [odin.services.config-service :as config]
            [odin.services.date-service :as date])
  (:import [software.amazon.awssdk.services.dynamodb DynamoDbClient DynamoDbClientBuilder]
           [software.amazon.awssdk.services.dynamodb.model
            AttributeValue
            PutItemRequest
            BatchWriteItemRequest WriteRequest PutRequest DeleteRequest
            BatchGetItemRequest KeysAndAttributes
            QueryRequest GetItemRequest DeleteItemRequest UpdateItemRequest
            ScanRequest DynamoDbException]
           [software.amazon.awssdk.http.urlconnection UrlConnectionHttpClient]
           [software.amazon.awssdk.auth.credentials StaticCredentialsProvider AwsBasicCredentials]
           [software.amazon.awssdk.regions Region]
           [java.net URI]))

(defn pr-edn-str [& xs]
  (binding [*print-length* nil
            *print-dup* nil
            *print-level* nil
            *print-readably* true]
    (apply pr-str xs)))

;;
;; AWS SDK v2 client
;;

(def dynamodb-client
  (delay
    (let [http-client (-> (UrlConnectionHttpClient/builder) (.build))
          ^Region region (Region/of (or (System/getenv "AWS_REGION") "eu-west-1"))
          ^DynamoDbClientBuilder builder (-> (DynamoDbClient/builder)
                                             (.httpClient http-client)
                                             (.region region))]
      (if @config/dynamodb-endpoint
        (-> builder
            (.endpointOverride (URI. ^String @config/dynamodb-endpoint))
            (.credentialsProvider
              (StaticCredentialsProvider/create
                (AwsBasicCredentials/create "local" "local")))
            (.build))
        (.build builder)))))


;;
;; Conversion helpers: Clojure maps <-> SDK v2 AttributeValue
;;

(defn- str->attr [s]
  (-> (AttributeValue/builder) (.s s) (.build)))

(defn- num->attr [n]
  (-> (AttributeValue/builder) (.n (str n)) (.build)))

(defn clj->attr-value
  "Convert {:S \"x\"}, {:N \"42\"}, or {:BOOL true} to an AttributeValue."
  [m]
  (cond
    (contains? m :S)    (str->attr (:S m))
    (contains? m :N)    (num->attr (:N m))
    (contains? m :BOOL) (-> (AttributeValue/builder) (.bool (boolean (:BOOL m))) (.build))
    :else (throw (ex-info "Unknown attribute type" {:value m}))))

(defn attr-value->clj
  "Convert an AttributeValue back to {:S \"x\"}, {:N \"42\"}, or {:BOOL b}."
  [^AttributeValue av]
  (let [t (str (.type av))]
    (case t
      "S"    {:S (.s av)}
      "N"    {:N (.n av)}
      "BOOL" {:BOOL (.bool av)}
      (throw (ex-info "Unsupported attribute type" {:type t})))))

(defn clj-item->sdk-item
  "Convert {:UserId {:S \"x\"}} to {\"UserId\" -> AttributeValue}."
  [item]
  (into {} (for [[k v] item]
             [(name k) (clj->attr-value v)])))

(defn sdk-item->clj-item
  "Convert {\"UserId\" -> AttributeValue} to {:UserId {:S \"x\"}}."
  [sdk-item]
  (into {} (for [[k v] sdk-item]
             [(keyword k) (attr-value->clj v)])))

;;
;; Data definitions (unchanged)
;;

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
  [input-str length pad-char]
  (let [s (str input-str)
        fmt (str "%" length "s")
        padded (format fmt s)]
    (s/replace-first padded #"^ *" (s/join (repeat (- length (count s)) pad-char)))))

;;
;; Config maps: [clj-key db-key db-type required? transform-fn]
;;

(def transaction-config [[:user-id              :UserId              :S    true  identity]
                         [:date                 :Timestamp           :S    true  +]
                         [:description          :Description         :S    false identity]
                         [:amount               :Amount              :N    true  identity]
                         [:category-id          :CategoryId          :S    false identity]
                         [:marked-by-filter?    :MarkedByFilter      :BOOL false identity]
                         [:source               :Source              :S    false identity]
                         [:tag-ids              :TagIds              :S    false identity]
                         [:filter-tag-ids       :FilterTagIds        :S    false identity]
                         [:account-id           :AccountId           :S    false identity]])

;; Investment transactions (stock buys/sells imported from a broker CSV).
;; Same composite-sortkey pattern as transactions: Timestamp = "unixtime#dateindex".
(def investment-config [[:user-id       :UserId        :S    true  identity]
                        [:date          :Timestamp     :S    true  +]
                        [:account-id    :AccountId     :S    false identity]
                        [:type          :Type          :S    false identity]
                        [:isin          :Isin          :S    false identity]
                        [:security-name :SecurityName  :S    false identity]
                        [:quantity       :Quantity       :N    false identity]
                        [:total-quantity :TotalQuantity  :N    false identity]
                        [:price          :Price          :N    false identity]
                        [:amount-nok     :AmountNok      :N    false identity]
                        [:fx-rate        :FxRate         :N    false identity]
                        [:source-id      :SourceId       :S    false identity]
                        ;; Running account balance (Saldo). Used by credit-account rows
                        ;; (type "credit") to carry the kredittkonto leverage balance.
                        [:balance        :Balance        :N    false identity]])

;; Grocery receipt line items (from Trumf / Rema / Coop loyalty apps).
(def grocery-config [[:user-id    :UserId    :S true  identity]
                     [:date       :Timestamp :S true  +]
                     [:account-id :AccountId :S false identity]
                     [:name       :Name      :S false identity]
                     [:amount     :Amount    :N false identity]
                     [:ean        :Ean       :S false identity]
                     [:quantity   :Quantity  :N false identity]
                     [:store      :Store     :S false identity]
                     [:provider   :Provider  :S false identity]
                     [:receipt-id :ReceiptId :S false identity]
                     [:source-id  :SourceId  :S false identity]])

;; Cached daily security prices (split-adjusted, already converted to NOK).
;; Note: uses :price-date (not :date) to avoid the composite-sortkey special-case.
(def security-price-config [[:isin       :Isin      :S true  identity]
                            [:price-date :Date      :S true  identity]
                            [:price-nok  :PriceNok  :N false identity]])

;; Per-user, per-ISIN manual settings (currently the Nordnet collateral ratio / belåningsgrad).
(def security-setting-config [[:user-id          :UserId          :S true  identity]
                              [:isin             :Isin            :S true  identity]
                              [:pawn-percentage  :PawnPercentage  :N false identity]])

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

(def loan-config [[:user-id         :UserId         :S]
                  [:id              :Id             :S]
                  [:name            :Name           :S]
                  [:nominal-rate    :NominalRate    :N]
                  [:balance         :Balance        :N]
                  [:monthly-payment :MonthlyPayment :N]
                  [:monthly-fee     :MonthlyFee     :N]
                  [:original-amount :OriginalAmount :N]
                  [:filter-text     :FilterText     :S]
                  [:filter-texts    :FilterTexts    :S]
                  [:interest-method :InterestMethod :S]
                  [:payment-history :PaymentHistory :S]])

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
(def loan-table-name "Loan")
(def investment-table-name "InvestmentTransaction")
(def grocery-table-name "GroceryItem")
(def security-price-table-name "SecurityPrice")
(def security-setting-table-name "SecuritySetting")

;;
;;  Write to database
;;

(defn write-item
  [table-name item]
  (let [^PutItemRequest req (-> (PutItemRequest/builder)
                                (.tableName table-name)
                                (.item (clj-item->sdk-item item))
                                (.build))]
    (.putItem ^DynamoDbClient @dynamodb-client req)))

(defn items->write-requests
  [items]
  (mapv (fn [item]
          (let [^PutRequest pr (-> (PutRequest/builder)
                                   (.item (clj-item->sdk-item item))
                                   (.build))]
            (-> (WriteRequest/builder)
                (.putRequest pr)
                (.build))))
        items))

(defn batch-write-request-items
  "Submit a request-items map (table-name -> list of WriteRequest) to DynamoDB
  via a single BatchWriteItem call, returning the result."
  [request-items]
  (let [^BatchWriteItemRequest request (-> (BatchWriteItemRequest/builder)
                                         (.requestItems request-items)
                                         (.build))]
    (.batchWriteItem ^DynamoDbClient @dynamodb-client request)))

(def ^:private batch-write-max-retries 5)

(defn write-items-to-db
  "Write a single batch (<=25 items) to DynamoDB. BatchWriteItem can return
  unprocessed items under throttling/load even when the call itself succeeds,
  so re-submit those with exponential backoff rather than dropping them."
  [table-name items]
  (try
    (loop [request-items {table-name (items->write-requests items)}
           attempt       0]
      (let [result      (batch-write-request-items request-items)
            unprocessed (.unprocessedItems result)]
        (if (or (nil? unprocessed) (.isEmpty unprocessed))
          result
          (if (< attempt batch-write-max-retries)
            (let [backoff-ms (* 100 (long (Math/pow 2 attempt)))
                  remaining  (reduce + (map count (vals unprocessed)))]
              (println (format "BatchWriteItem returned %d unprocessed item(s); retrying (attempt %d/%d) after %dms"
                               remaining (inc attempt) batch-write-max-retries backoff-ms))
              (Thread/sleep backoff-ms)
              (recur (into {} unprocessed) (inc attempt)))
            (do
              (println "Error: items still unprocessed after" batch-write-max-retries "retries:" unprocessed)
              (throw (ex-info "BatchWriteItem failed: items remain unprocessed after retries"
                              {:unprocessed-items unprocessed})))))))
    (catch DynamoDbException e
      (println "Error writing to DynamoDB:" (.getMessage e))
      (println "Problematic items:" items)
      (throw e))))

(defn store-items [table-name items]
  (let [item-groups (partition 25 25 nil items)]
    (doall (map #(write-items-to-db table-name %) item-groups))))

;;
;; Item conversion helpers (unchanged logic)
;;

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
        _ (println "Sample db-transaction:" (first db-transactions))]
    (let [store-result (store-items transaction-table-name db-transactions)]
    transactions)))

;;
;;  Store category
;;

(defn store-category [category]
  (let [db-category (item->db-item category-config category)]
    (store-items category-table-name [db-category])))

;;
;; Delete transactions
;;

(defn delete-item
  "Delete a single item from a DynamoDB table. key-map is in Clojure format,
   e.g. {:UserId {:S \"x\"} :Timestamp {:S \"y\"}}."
  [table-name key-map]
  (let [^DeleteItemRequest req (-> (DeleteItemRequest/builder)
                                   (.tableName table-name)
                                   (.key (clj-item->sdk-item key-map))
                                   (.build))]
    (.deleteItem ^DynamoDbClient @dynamodb-client req)))

(defn delete-transactions
  [transactions]
  (let [max-batch-size 25
        table-name transaction-table-name
        partition-and-sort-keys (->> transactions
                                     transactions->db-transactions
                                     (map #(vector (:UserId %) (:Timestamp %))))
        write-requests (mapv (fn [[pk sk]]
                               (let [^DeleteRequest dr (-> (DeleteRequest/builder)
                                                           (.key {"UserId"    (clj->attr-value pk)
                                                                  "Timestamp" (clj->attr-value sk)})
                                                           (.build))]
                                 (-> (WriteRequest/builder)
                                     (.deleteRequest dr)
                                     (.build))))
                             partition-and-sort-keys)]
    (letfn [(process-batch [reqs]
              (let [batch-req (-> (BatchWriteItemRequest/builder)
                                 (.requestItems {table-name (vec reqs)})
                                 (.build))
                    response (.batchWriteItem ^DynamoDbClient @dynamodb-client ^BatchWriteItemRequest batch-req)
                    unprocessed (get (.unprocessedItems response) table-name)]
                (when (seq unprocessed)
                  (process-batch unprocessed))))]
      (doseq [group (partition-all max-batch-size write-requests)]
        (process-batch group))))
  nil)

;;
;;  Read from database
;;

(defn read-batch-items
  [table-name key-items]
  (let [sdk-keys (mapv clj-item->sdk-item key-items)
        keys-and-attrs (-> (KeysAndAttributes/builder)
                           (.keys sdk-keys)
                           (.build))
        request (-> (BatchGetItemRequest/builder)
                    (.requestItems {table-name keys-and-attrs})
                    (.build))
        response (.batchGetItem ^DynamoDbClient @dynamodb-client ^BatchGetItemRequest request)]
    (println response)
    response))

(defn query-all-items
  [table-name partition-key partition-value]
  (try
    (let [request (-> (QueryRequest/builder)
                      (.tableName table-name)
                      (.keyConditionExpression "#pk = :v")
                      (.expressionAttributeNames {"#pk" partition-key})
                      (.expressionAttributeValues {":v" (clj->attr-value partition-value)})
                      (.build))
          result (.query ^DynamoDbClient @dynamodb-client ^QueryRequest request)]
      (map sdk-item->clj-item (.items result)))
    (catch Exception e
      (println "An error occurred while querying DynamoDB:" (.getMessage e))
      nil)))

(defn query-items-greater-than
  [table-name partition-key-name partition-key-value sort-key-name sort-key-value]
  (println sort-key-value)
  (let [request (-> (QueryRequest/builder)
                    (.tableName table-name)
                    (.keyConditionExpression "#pk = :partitionval AND #sk > :sortval")
                    (.expressionAttributeValues {":partitionval" (clj->attr-value partition-key-value)
                                                 ":sortval"      (clj->attr-value sort-key-value)})
                    (.expressionAttributeNames {"#pk" partition-key-name
                                                "#sk" sort-key-name})
                    (.build))
        response (.query ^DynamoDbClient @dynamodb-client ^QueryRequest request)]
    (map sdk-item->clj-item (.items response))))

(defn query-items-greater-than2
  [table-name partition-key-name partition-key-value sort-key-name sort-key-value attributes retrieve-all?]
  (let [attr-names (into {} (map (fn [attr] [(str "#attr_" attr) attr]) attributes))
        projection-expr (s/join ", " (keys attr-names))
        expr-attr-names (merge {"#pk" partition-key-name "#sk" sort-key-name} attr-names)
        expr-attr-values {":partitionval" (str->attr partition-key-value)
                          ":sortval"      (str->attr sort-key-value)}]
    (loop [items []
           last-key nil
           iteration 0]
      (let [start-time (System/currentTimeMillis)
            builder (-> (QueryRequest/builder)
                        (.tableName table-name)
                        (.keyConditionExpression "#pk = :partitionval AND #sk > :sortval")
                        (.expressionAttributeNames expr-attr-names)
                        (.expressionAttributeValues expr-attr-values)
                        (.projectionExpression projection-expr))
            builder (if last-key (.exclusiveStartKey builder last-key) builder)
            ^QueryRequest req (.build builder)
            response (.query ^DynamoDbClient @dynamodb-client req)
            response-items (map sdk-item->clj-item (.items response))
            new-items (concat items response-items)
            elapsed-time (- (System/currentTimeMillis) start-time)]
        (println "Query iteration" iteration "- Retrieved" (count response-items) "items in" elapsed-time "ms")
        (if (and (.hasLastEvaluatedKey response) retrieve-all?)
          (recur new-items (.lastEvaluatedKey response) (inc iteration))
          (do
            (println "Query completed - Total items:" (count new-items) "in" (inc iteration) "iterations")
            new-items))))))

(defn translate-key-val [m [key db-key db-type _]]
  (if-let [db-val (get m db-key)]
    (let [val-trans-fx (case db-type
                         :S identity
                         :N #(Double/parseDouble %)
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

(defn iso-date->sortkey [iso-date]
  (-> iso-date
      (str "T00:00:00")
      (date/localtime->unixtime)
      (pad-left 16 "0")
      (str "#0000")))

(defn get-latest-transaction-date
  "Returns the ISO date string of the most recent transaction, or nil if none exist."
  [user-id]
  (let [req (-> (QueryRequest/builder)
                (.tableName transaction-table-name)
                (.keyConditionExpression "#pk = :partitionval")
                (.expressionAttributeNames {"#pk" "UserId" "#ts" "Timestamp"})
                (.expressionAttributeValues {":partitionval" (str->attr user-id)})
                (.projectionExpression "#ts")
                (.scanIndexForward false)
                (.limit (int 1))
                (.build))
        response (.query ^DynamoDbClient @dynamodb-client req)
        items (.items response)]
    (when (seq items)
      (let [timestamp-str (-> items first (.get "Timestamp") .s)
            ;; Timestamp is "unixtime#dateindex", extract unixtime
            unixtime (Long/parseLong (first (clojure.string/split timestamp-str #"#")))]
        (date/unixtime->iso-date unixtime)))))

(defn get-transactions-after [user-id iso-date retrieve-all?]
  (println "=== get-transactions-after ===")
  (println "user-id:" user-id ", iso-date:" iso-date ", retrieve-all?:" retrieve-all?)
  (let [table-name transaction-table-name
        db-transactions (query-items-greater-than2 table-name
                                           "UserId" user-id
                                           "Timestamp" (iso-date->sortkey iso-date)
                                                   ["Timestamp" "Description" "Amount" "CategoryId" "MarkedByFilter" "TagIds" "FilterTagIds" "AccountId"]
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

;;
;; Investment transactions
;;

(defn store-investment-transactions [investment-transactions]
  (let [db-items (map #(item->db-item investment-config %) investment-transactions)]
    (store-items investment-table-name db-items)
    investment-transactions))

(defn get-investment-transactions
  "Return all investment transactions for a user, oldest first."
  [user-id]
  (->> (query-all-items investment-table-name "UserId" {:S user-id})
       (sort-by #(-> % :Timestamp :S))
       (map #(reduce translate-key-val % investment-config))
       (map split-composite-sortkey-date)
       (map #(assoc % :user-id user-id))))

(defn get-investment-source-ids
  "Return the set of Nordnet source-ids already stored for a user (for import dedupe)."
  [user-id]
  (->> (get-investment-transactions user-id)
       (keep :source-id)
       set))

;;
;; Grocery items (receipt lines from loyalty apps)
;;

(defn store-grocery-items [items]
  (let [db-items (map #(item->db-item grocery-config %) items)]
    (store-items grocery-table-name db-items)
    items))

(defn get-grocery-items
  "Return all grocery line items for a user, oldest first."
  [user-id]
  (->> (query-all-items grocery-table-name "UserId" {:S user-id})
       (sort-by #(-> % :Timestamp :S))
       (map #(reduce translate-key-val % grocery-config))
       (map split-composite-sortkey-date)
       (map #(assoc % :user-id user-id))))

;;
;; Security prices
;;

(defn store-security-prices [prices]
  (let [db-items (map #(item->db-item security-price-config %) prices)]
    (store-items security-price-table-name db-items)
    prices))

(defn get-security-prices
  "Return all cached daily prices for an ISIN as {:isin :price-date :price-nok}."
  [isin]
  (->> (query-all-items security-price-table-name "Isin" {:S isin})
       (map #(reduce translate-key-val % security-price-config))))

;;
;; Security settings (per-user collateral ratio)
;;

;; Reserved Isin key (in SecuritySetting) used to stash per-user metadata, not a real ratio.
(def prices-refreshed-isin "__prices_refreshed_at__")

(defn put-security-setting [user-id isin pawn-percentage]
  (write-item security-setting-table-name
              (item->db-item security-setting-config
                             {:user-id user-id :isin isin :pawn-percentage pawn-percentage})))

(defn get-security-settings-raw [user-id]
  (->> (query-all-items security-setting-table-name "UserId" {:S user-id})
       (map #(reduce translate-key-val % security-setting-config))))

(defn get-security-settings
  "Per-ISIN collateral ratios for a user (excludes reserved `__`-prefixed metadata keys)."
  [user-id]
  (remove #(s/starts-with? (str (:isin %)) "__") (get-security-settings-raw user-id)))

(defn set-prices-refreshed-at [user-id ms]
  (put-security-setting user-id prices-refreshed-isin (double ms)))

(defn get-prices-refreshed-at
  "Epoch-ms of the user's last successful price refresh, or nil."
  [user-id]
  (some->> (get-security-settings-raw user-id)
           (filter #(= prices-refreshed-isin (:isin %)))
           first :pawn-percentage long))

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

(defn delete-category [user-id category-id]
  (delete-item category-table-name {:UserId {:S user-id} :Id {:S category-id}}))

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
  (delete-item report-table-name {:UserId {:S user-id} :Id {:S report-id}}))

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
  (delete-item tag-table-name {:UserId {:S user-id} :Id {:S tag-id}}))

;;
;; Loans
;;

(defn store-loan [loan]
  (let [db-loan (item->db-item loan-config loan)]
    (store-items loan-table-name [db-loan])))

(defn get-loans [user-id]
  (let [db-loans (query-all-items loan-table-name "UserId" {:S user-id})]
    (->> db-loans
         (map #(reduce translate-key-val % loan-config))
         (map #(parse-edn-field % :payment-history))
         (map #(parse-edn-field % :filter-texts)))))

(defn delete-loan [user-id loan-id]
  (delete-item loan-table-name {:UserId {:S user-id} :Id {:S loan-id}}))

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
  (delete-item filter-table-name {:UserId {:S user-id} :Id {:S filter-id}}))

(defn delete-filters-by-category [user-id category-id]
  (let [all-filters (get-filters user-id)
        category-filters (filter #(= (:category-id %) category-id) all-filters)]
    (doseq [f category-filters]
      (delete-filter user-id (:id f)))))

(defn list-items [table-name]
  (let [request (-> (ScanRequest/builder) (.tableName table-name) (.build))
        response (.scan ^DynamoDbClient @dynamodb-client ^ScanRequest request)]
    (map sdk-item->clj-item (.items response))))

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
  (let [request (-> (GetItemRequest/builder)
                    (.tableName user-table-name)
                    (.key {"UserId" (str->attr user-id)})
                    (.build))
        response (.getItem ^DynamoDbClient @dynamodb-client ^GetItemRequest request)]
    (when (seq (.item response))
      (let [item (sdk-item->clj-item (.item response))]
        {:user-id       (get-in item [:UserId :S])
         :email         (get-in item [:Email :S])
         :password-hash (get-in item [:PasswordHash :S])
         :created-at    (get-in item [:CreatedAt :S])}))))

(defn get-user-by-email [email]
  (let [request (-> (QueryRequest/builder)
                    (.tableName user-table-name)
                    (.indexName "EmailIndex")
                    (.keyConditionExpression "#e = :email")
                    (.expressionAttributeNames {"#e" "Email"})
                    (.expressionAttributeValues {":email" (str->attr email)})
                    (.build))
        response (.query ^DynamoDbClient @dynamodb-client ^QueryRequest request)]
    (when-let [sdk-item (first (.items response))]
      (let [item (sdk-item->clj-item sdk-item)]
        {:user-id       (get-in item [:UserId :S])
         :email         (get-in item [:Email :S])
         :password-hash (get-in item [:PasswordHash :S])
         :created-at    (get-in item [:CreatedAt :S])}))))

;;
;; Accounts (bank connections)
;;

(defn put-account [{:keys [user-id account-id provider account-name
                           client-id client-secret redirect-uri
                           token-data bank-account-key account-number last-sync created-at]}]
  (write-item account-table-name
              (cond-> {:UserId    {:S user-id}
                       :AccountId {:S account-id}
                       :Provider  {:S provider}
                       :CreatedAt {:S created-at}}
                account-name     (assoc :AccountName    {:S account-name})
                client-id        (assoc :ClientId       {:S client-id})
                client-secret    (assoc :ClientSecret   {:S client-secret})
                redirect-uri     (assoc :RedirectUri    {:S redirect-uri})
                token-data       (assoc :TokenData      {:S token-data})
                bank-account-key (assoc :BankAccountKey  {:S bank-account-key})
                account-number   (assoc :AccountNumber  {:S account-number})
                last-sync        (assoc :LastSync       {:S last-sync}))))

(defn get-accounts-for-user [user-id]
  (let [request (-> (QueryRequest/builder)
                    (.tableName account-table-name)
                    (.keyConditionExpression "#uid = :uid")
                    (.expressionAttributeNames {"#uid" "UserId"})
                    (.expressionAttributeValues {":uid" (str->attr user-id)})
                    (.build))
        response (.query ^DynamoDbClient @dynamodb-client ^QueryRequest request)
        items (map sdk-item->clj-item (.items response))]
    (mapv (fn [item]
            (cond-> {:user-id    (get-in item [:UserId :S])
                     :account-id (get-in item [:AccountId :S])
                     :provider   (get-in item [:Provider :S])
                     :created-at (get-in item [:CreatedAt :S])}
              (get item :AccountName)    (assoc :account-name    (get-in item [:AccountName :S]))
              (get item :ClientId)       (assoc :client-id       (get-in item [:ClientId :S]))
              (get item :ClientSecret)   (assoc :client-secret   (get-in item [:ClientSecret :S]))
              (get item :RedirectUri)    (assoc :redirect-uri    (get-in item [:RedirectUri :S]))
              (get item :TokenData)      (assoc :token-data      (get-in item [:TokenData :S]))
              (get item :BankAccountKey) (assoc :bank-account-key (get-in item [:BankAccountKey :S]))
              (get item :AccountNumber)  (assoc :account-number  (get-in item [:AccountNumber :S]))
              (get item :LastSync)       (assoc :last-sync       (get-in item [:LastSync :S]))))
          items)))

(defn get-account [user-id account-id]
  (let [request (-> (GetItemRequest/builder)
                    (.tableName account-table-name)
                    (.key {"UserId"    (str->attr user-id)
                           "AccountId" (str->attr account-id)})
                    (.build))
        response (.getItem ^DynamoDbClient @dynamodb-client ^GetItemRequest request)]
    (when (seq (.item response))
      (let [item (sdk-item->clj-item (.item response))]
        (cond-> {:user-id    (get-in item [:UserId :S])
                 :account-id (get-in item [:AccountId :S])
                 :provider   (get-in item [:Provider :S])
                 :created-at (get-in item [:CreatedAt :S])}
          (get item :AccountName)    (assoc :account-name    (get-in item [:AccountName :S]))
          (get item :ClientId)       (assoc :client-id       (get-in item [:ClientId :S]))
          (get item :ClientSecret)   (assoc :client-secret   (get-in item [:ClientSecret :S]))
          (get item :RedirectUri)    (assoc :redirect-uri    (get-in item [:RedirectUri :S]))
          (get item :TokenData)      (assoc :token-data      (get-in item [:TokenData :S]))
          (get item :BankAccountKey) (assoc :bank-account-key (get-in item [:BankAccountKey :S]))
          (get item :AccountNumber)  (assoc :account-number  (get-in item [:AccountNumber :S]))
          (get item :LastSync)       (assoc :last-sync       (get-in item [:LastSync :S])))))))

(defn update-account-tokens [user-id account-id token-data-json]
  (let [req ^UpdateItemRequest (-> (UpdateItemRequest/builder)
                                   (.tableName account-table-name)
                                   (.key {"UserId"    (str->attr user-id)
                                          "AccountId" (str->attr account-id)})
                                   (.updateExpression "SET TokenData = :td")
                                   (.expressionAttributeValues {":td" (str->attr token-data-json)})
                                   (.build))]
    (.updateItem ^DynamoDbClient @dynamodb-client ^UpdateItemRequest req)))

(defn update-account-client-secret [user-id account-id client-secret]
  (let [req ^UpdateItemRequest (-> (UpdateItemRequest/builder)
                                   (.tableName account-table-name)
                                   (.key {"UserId"    (str->attr user-id)
                                          "AccountId" (str->attr account-id)})
                                   (.updateExpression "SET ClientSecret = :cs")
                                   (.expressionAttributeValues {":cs" (str->attr client-secret)})
                                   (.build))]
    (.updateItem ^DynamoDbClient @dynamodb-client ^UpdateItemRequest req)))

(defn update-account-bank-key [user-id account-id bank-account-key]
  (let [req ^UpdateItemRequest (-> (UpdateItemRequest/builder)
                                   (.tableName account-table-name)
                                   (.key {"UserId"    (str->attr user-id)
                                          "AccountId" (str->attr account-id)})
                                   (.updateExpression "SET BankAccountKey = :bk")
                                   (.expressionAttributeValues {":bk" (str->attr bank-account-key)})
                                   (.build))]
    (.updateItem ^DynamoDbClient @dynamodb-client ^UpdateItemRequest req)))

(defn update-account-number [user-id account-id account-number]
  (let [req ^UpdateItemRequest (-> (UpdateItemRequest/builder)
                                   (.tableName account-table-name)
                                   (.key {"UserId"    (str->attr user-id)
                                          "AccountId" (str->attr account-id)})
                                   (.updateExpression "SET AccountNumber = :an")
                                   (.expressionAttributeValues {":an" (str->attr account-number)})
                                   (.build))]
    (.updateItem ^DynamoDbClient @dynamodb-client ^UpdateItemRequest req)))

(defn update-account-last-sync [user-id account-id last-sync]
  (let [req ^UpdateItemRequest (-> (UpdateItemRequest/builder)
                                   (.tableName account-table-name)
                                   (.key {"UserId"    (str->attr user-id)
                                          "AccountId" (str->attr account-id)})
                                   (.updateExpression "SET LastSync = :ls")
                                   (.expressionAttributeValues {":ls" (str->attr last-sync)})
                                   (.build))]
    (.updateItem ^DynamoDbClient @dynamodb-client ^UpdateItemRequest req)))

(defn delete-account [user-id account-id]
  (delete-item account-table-name {:UserId {:S user-id} :AccountId {:S account-id}}))

;;
;; Scan table (used for migration)
;;

(defn scan-table-for-user [table-name user-id]
  (loop [items [] last-key nil]
    (let [builder (-> (ScanRequest/builder)
                      (.tableName table-name)
                      (.filterExpression "UserId = :uid")
                      (.expressionAttributeValues {":uid" (str->attr user-id)}))
          builder (if last-key (.exclusiveStartKey builder last-key) builder)
          req ^ScanRequest (.build builder)
          response (.scan ^DynamoDbClient @dynamodb-client ^ScanRequest req)
          new-items (map sdk-item->clj-item (.items response))
          all-items (concat items new-items)]
      (if (.hasLastEvaluatedKey response)
        (recur all-items (.lastEvaluatedKey response))
        (vec all-items)))))

(defn- delete-all-items-for-user
  [table-name pk-attr sk-attr user-id]
  (let [items (scan-table-for-user table-name user-id)]
    (doseq [item items]
      (let [key-map (cond-> {pk-attr (get item pk-attr)}
                      sk-attr (assoc sk-attr (get item sk-attr)))]
        (delete-item table-name key-map)))
    (count items)))

(defn delete-all-user-data
  [user-id]
  (let [counts (atom {})]
    (swap! counts assoc "Transaction" (delete-all-items-for-user transaction-table-name :UserId :Timestamp user-id))
    (swap! counts assoc "Category" (delete-all-items-for-user category-table-name :UserId :Id user-id))
    (swap! counts assoc "Report" (delete-all-items-for-user report-table-name :UserId :Id user-id))
    (swap! counts assoc "Tag" (delete-all-items-for-user tag-table-name :UserId :Id user-id))
    (swap! counts assoc "Loan" (delete-all-items-for-user loan-table-name :UserId :Id user-id))
    (swap! counts assoc "Filter" (delete-all-items-for-user filter-table-name :UserId :Id user-id))
    (swap! counts assoc "Account" (delete-all-items-for-user account-table-name :UserId :AccountId user-id))
    (swap! counts assoc "GroceryItem" (delete-all-items-for-user grocery-table-name :UserId :Timestamp user-id))
    (delete-item user-table-name {:UserId {:S user-id}})
    (swap! counts assoc "User" 1)
    @counts))
