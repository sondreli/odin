(ns odin.services.transaction-service
  (:require [clojure.data.json :as json]
            [clojure.tools.trace :as trace]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            ;; [datomic.client.api :as d]
            [odin.db :as db]
            [odin.db2 :as db2]
            [common.category-service :as category]
            [odin.services.date-service :as date]
            [odin.services.auth-service :as auth]
            [odin.services.merge-service :as merge]
            [clojure.test :as test :refer [deftest is do-report]]
            [odin.services.transaction-service :as transaction]))


(defn extract-body [http-response]
  (if (and (contains? http-response :body)
           (-> http-response :body count (> 0)))
    (:body http-response)
    (let [_ (println "http-response does not contain body: " http-response)]
      nil)))

(defn read-json [body-str]
  (if (and (some? body-str) (-> body-str count (> 0)))
    (json/read-str body-str :key-fn keyword)
    (let [_ (println "http-response contained no value in body: " body-str)]
      nil)))

(defn extract-transactions [body]
  (let [transactions (:transactions body)]
    (if (some? transactions)
      (reverse transactions)
      (let [_ (println "http-response does not contain transactions: " body)]
        nil))))

(defn process-response [http-response]
  ;; (-> (:body http-response)
  ;;     (json/read-str :key-fn keyword)
  ;;     :transactions
  ;;     reverse)
  (-> http-response extract-body extract-transactions))

(defn retrieve-bank-transactions-from [date {token :access_token} account_key]
  (let [date-str (-> date .toLocalDate str)
        http-response (client/get "https://api.sparebank1.no/personal/banking/transactions"
                                  {;:accept "application/vnd.sparebank1.v1+json; charset=utf-8"
                                   :query-params {"accountKey" account_key
                                                  "fromDate" date-str}
                                   :headers {:authorization (str "Bearer " token)
                                             :accept "application/vnd.sparebank1.v1+json; charset=utf-8"}})
        transactions (-> http-response extract-body read-json extract-transactions)]
    transactions))

(defn retrieve-bank-transactions-from-to [from-date to-date {token :access_token} account_key]
  (let [from-date-str (-> from-date .toLocalDate str)
        to-date-str (-> to-date .toLocalDate str)
        http-response (client/get "https://api.sparebank1.no/personal/banking/transactions"
                                  {;:accept "application/vnd.sparebank1.v1+json; charset=utf-8"
                                   :query-params {"accountKey" account_key
                                                  "fromDate" from-date-str
                                                  "toDate" to-date-str}
                                   :headers {:authorization (str "Bearer " token)
                                             :accept "application/vnd.sparebank1.v1+json; charset=utf-8"}})
        transactions (-> http-response extract-body read-json extract-transactions)]
    transactions))

(defn retrieve_accounts [{token :access_token}]
  ;;(client/get "https://api.sparebank1.no/personal/banking/accounts?includeNokAccounts=true&includeCurrencyAccounts=true"
  (client/get "https://api.sparebank1.no/personal/banking/accounts/default"
              {;:accept "application/vnd.sparebank1.v1+json; charset=utf-8"
               :headers {:authorization (str "Bearer " token)}}))


(defn retrieve_transaction_details [{token :access_token} transaction_id]
  (println "retrieveing trans details")
  (let [http-response (client/get (str "https://api.sparebank1.no/personal/banking/transactions/" transaction_id "/details")
              {;:accept "application/vnd.sparebank1.v1+json; charset=utf-8"
               :headers {:authorization (str "Bearer " token)
                         :accept "application/vnd.sparebank1.v1+json; charset=utf-8"}})
        transaction (-> http-response extract-body read-json)]
    transaction)
  )

(defn add-transaction-details [token transaction]
  (let [id (:id transaction)
        transaction-details (retrieve_transaction_details token id)]
    (merge transaction transaction-details)))

(defn enrich-transactions [token transactions]
  (let [enrich? (fn [transaction] (and (contains? transaction :typeCode)
                                       (-> transaction :typeCode (= "PAYCTDOMOUPKID"))))
        enrich (fn [trans] (Thread/sleep 100000) (add-transaction-details token trans))]
    (->> transactions
         (map #(if (enrich? %) (enrich %) %)))))


(let [start-date (-> 1703631600000 date/unixtime->localtime (date/subtract-days 14))
      trans-date (-> 1703631600000 date/unixtime->localtime)
      ]
  (.isEqual start-date trans-date))

(defn transactions-from [transactions date]
  (println "transactions-from: "  date " " (count transactions))
  (let [isOnOrAfter (fn [date-a date-b] (or (.isAfter date-a date-b)
                                            (.isEqual date-a date-b)))]
    (filter #(-> % :date date/unixtime->localtime (isOnOrAfter date)) transactions)))

(defn replace-nil-description [transaction]
  (if (-> transaction :description some?)
    transaction
    (let [destination-account (-> transaction :source :remoteAccountNumber)
          desc (str "Overføring til: " destination-account)]
      (assoc transaction :description desc))))

(defn last-update-date [first-date transactions days-back]
  (if (empty? transactions)
    (date/iso-date-str->date first-date)
    (let [last-date-in-db (-> transactions last :date date/unixtime->localtime)
          start-date (-> last-date-in-db (date/subtract-days days-back))]
      start-date)))

;; (defn append-new-transactions2 [transactions-in-db token account-key]
;;   (let [days-back 14
;;         last-date-in-db (-> transactions-in-db last :date date/unixtime->localtime)
;;         start-date (-> last-date-in-db (date/subtract-days days-back))
;;         latest-transactions-in-db (transactions-from transactions-in-db start-date)
;;         _ (println "latest-transactions-in-db: " (count latest-transactions-in-db))
;;         latest-transactions-from-bank (retrieve-bank-transactions-from start-date token account-key)
;;         [replace-transactions
;;          new-transactions] (merge/process-transactions-from-bank latest-transactions-in-db
;;                                                                  latest-transactions-from-bank)
;;         categories (db/get-categories)
;;         categorized-new-transactions (->> new-transactions
;;                                           (category/add-categories categories)
;;                                           (map replace-nil-description))
;;         categorized-replace-transactions (->> replace-transactions
;;                                               (category/add-categories categories)
;;                                               (map replace-nil-description))]
;;     (println "append-new-transactions2: " (count transactions-in-db) (count new-transactions))
;;     ;; (println "categorized-replace-transactions: " categorized-replace-transactions)
;;     (println "append-new-transactions2: have category " (filter #(-> % :category-id some?) transactions-in-db))
;;     ; find the old one, already have it in retrieved from db
;;     ; move it's category over to new transaction
;;     ; create the :amount and :description keys
;;     ; now you can delete the old entry
;;     ; and add the new one
;;     ; and return the new transactions as part of all transactions to frontend
;;     ;; (db/replace-transactions categorized-replace-transactions) ; store and return with db-id
;;     ;; (db/store-transactions2 categorized-new-transactions) ;; should categorize before store and return the trans with db-id
;;     (db2/store-transactions categorized-new-transactions) ;; should categorize before store and return the trans with db-id
;;     ;(concat transactions-in-db categorized-new-transactions) ; transactions-in-db are outdated after replace-transactions
;;     (db2/get-transactions-after "2022-01-01")
;;     ;; should sort here
;;     ))

(defn retrieve-and-store-all-transactions [token account-key]
  (println "retrieve-and-store-all-transactions")
  (let [transactions (retrieve-bank-transactions-from "2022-05-01" token account-key)
        ;; enriched-transactions (enrich-transactions token transactions)
        tx-result (db/store-transactions transactions)]
    transactions))

(defn new->internal-transaction [index new-transaction]
  {:user-id "xxx"
   :amount (-> new-transaction :amount str Double/parseDouble)
   :date (:date new-transaction)
   :date-index index
   :description (:description new-transaction)
   :source new-transaction})

(defn index-date-group-transactions [new-transactions-date-group last-index]
  (->> new-transactions-date-group
       (map-indexed (fn [idx group] [(+ idx last-index) group]))
       (map #(apply new->internal-transaction %))))

(defn add-last-index [date-indexes [date date-group]]
  (if (contains? date-indexes date)
    [date-group (-> date-indexes (get date) last inc)]
    [date-group 0]))

(defn new->internal-transactions [updated-db-transactions new-transactions]
  (let [date-indexes (->> updated-db-transactions
                          (group-by :date)
                          (map (fn [[a b]] [a (->> b (map :date-index) sort)]))
                          (into {}))
        add-last-index-fx (partial add-last-index date-indexes)]
        ;; (println date-indexes)
    (->> new-transactions
         (group-by :date)
         (map add-last-index-fx)
         (mapcat #(apply index-date-group-transactions %))
         (sort-by :date))))

(defn update-db-transaction [transaction updates-map]
  (let [key (str (:date transaction) "#" (:date-index transaction))]
    (if (contains? updates-map key)
      (merge transaction (get updates-map key))
      transaction)))

(defn update-db-transactions [db-transactions updates]
  (let [m (->> updates
               (map (fn [u] [(str (:date u) "#" (:date-index u)) u]))
               (into {}))]
    (map #(update-db-transaction % m) db-transactions)))

(defn old-transactions [transactions last-udpate-date]
  (let [last-unixtime (date/date->unixtime last-udpate-date)]
    (filter #(< (:date %) last-unixtime) transactions)))

(defn trans->debug [trans]
  (->> trans
       (map #(select-keys % [:date :date-index :amount]))
       (map #(update % :date date/unixtime->iso-date))
       reverse))

(defn print-time [start-time msg] 
  (println "Elapsed time:" (- (System/currentTimeMillis) start-time) "ms " msg))

; when no db-transactions
; retrieve all from bank
; else retrieve latest from bank

; retrieve-all-from-bank
; retrieve each year starting from now and continue backwards until no transactions are found
; store each year
; return all transactions

; retrieve from db
; retrieve from bank (from date, from-last-transaction or default first date)
; merge (only if some db-transactions)
; store (new and updates)
; return 
(defn get-transactions2 [token account-key]
  (let [;start-time (System/currentTimeMillis)
        categories (db2/get-categories)
        older-transactions (retrieve-bank-transactions-from-to (date/iso-date->local-datetime "2020-01-01") (date/iso-date->local-datetime "2021-01-02") token account-key)
        _ (println "older-transactions: " (count older-transactions))
        _ (println "older-transactions: " (take 3 older-transactions))
        _ (db2/store-transactions (->> older-transactions
                                       (new->internal-transactions nil)
                                       (category/add-categories (db2/get-categories))
                                       (map replace-nil-description)))
        first-date "2020-01-01"
        db-transactions (db2/get-transactions-after first-date)
        ;_ (print-time start-time "1")
        _ (println "db-transactions2: " (take 10 db-transactions))
        last-update-date (last-update-date first-date db-transactions 14)
        latest-db-transactions (transactions-from db-transactions last-update-date)
        latest-bank-transactions (retrieve-bank-transactions-from last-update-date token account-key)
        ; date-index is added if match towards db-transaction is found, thus it is only added for updates
        ; new transactions will have the date-index added as a continium from last update
        [updates, new] (merge/process-transactions-from-bank latest-db-transactions latest-bank-transactions)
        updated-db-transactions (update-db-transactions latest-db-transactions updates)
        internal-new (new->internal-transactions updated-db-transactions new)
        ; add-categories to internal-new. updated should be recategorized as the description may have changed
        _ (println "get-transactions2: categories: " categories)
        categorized-updates-and-new (->> (concat updated-db-transactions internal-new)
                                      (category/add-categories categories)
                                      (map replace-nil-description))
        ;; categorized-updates (->> updated-db-transactions
        ;;                               (category/add-categories categories)
        ;;                               (map replace-nil-description))
        old (old-transactions db-transactions last-update-date)
        _ (db2/store-transactions categorized-updates-and-new)
        all-transactions (concat old categorized-updates-and-new)
        all-transactions-no-source (map #(dissoc % :source) all-transactions)
        ]
        (println "Old: " (count old) " updates: " (count updated-db-transactions) " new: " (count internal-new))
        ;; (println "last 10 from bank: ")
        ;; (pp/pprint (take 20 (trans->debug latest-bank-transactions)))
        ;; (println  "last 10 ext updates: ")
        ;; (pp/pprint (take 10 (trans->debug latest-db-transactions)))
        ;; (println  "last 10 updates: ")
        ;; (pp/pprint (take 20 (trans->debug updated-db-transactions)))
        ;; (println  "last 10 internal-new: ")
        ;; (pp/pprint (take 20 (trans->debug internal-new)))
        ;; (println "updates and new: ")
        ;; (pp/pprint (take 20 (trans->debug categorized-updates-and-new)))
        ;; (println "all-transactions: ")
        ;; (pp/pprint (take 20 (trans->debug all-transactions)))
    all-transactions-no-source))

;; (defn get-transactions [token account-key] ; maybe config?
;;   (println "get-transactions: " (count (db2/get-transactions-after "2022-01-01")))
;;   (if-let [transactions-in-db (not-empty (db2/get-transactions-after "2022-01-01"))]
;;     (append-new-transactions2 transactions-in-db token account-key)
;;     (retrieve-and-store-all-transactions token account-key)))

;; get-all-transactions
;; get all from db
;; get time diff from last entry to now
;; retrieve from bank the timediff
;; filter out already retrieved transactions
;; store timediff
;; merge all-transactions and return

(defn search_transaction [transaction key sub_str]
  (let [desc (get transaction key)]
    (and (contains? transaction key) (s/includes? (s/lower-case desc) sub_str))))

(defn only_fields [transaction & fields]
  (if (empty? fields)
    transaction
    (select-keys transaction fields)))

(defn filter_transactions [transactions key sub_str]
  (->> transactions
       (filter #(search_transaction % key sub_str))
       (map #(only_fields % :amount :description))))


(defn get-all-transactions []
  (let [tokens (auth/get_tokens "session_tokens.txt")
        _ (println "retrieve accounts")
        accounts (retrieve_accounts tokens)
        body_str (-> accounts :body)
        body (json/read-str body_str :key-fn keyword)
        account_key (:key body)
        _ (println "account_key: " account_key)
        ;; transactions_response (retrieve_transactions tokens account_key)
        ;; all_transactions (:transactions (json/read-str (:body transactions_response) :key-fn keyword))
        all-transactions (get-transactions2 tokens account_key)
        ;transactions (filter_transactions all-transactions :description "google")
        ]

    ;; (pp/pprint accounts)
    ;; (pp/pprint (take 1 all-transactions))
    ;; (pp/pprint transactions)
    ;; (println account_key)
    (pp/pprint (take 3 all-transactions))
    ))

(defn transaction_handler_test [req]
  {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str ["hello"])})

(defn transaction_handler [request]
  (pp/pprint request)
  (let [tokens (auth/get_tokens "session_tokens.txt")
        _ (println "retrieve accounts")
        accounts (retrieve_accounts tokens)
        body_str (-> accounts :body)
        body (json/read-str body_str :key-fn keyword)
        account_key (:key body)
        all-transactions (get-transactions2 tokens account_key)
        ;; _ (println (take 3 all-transactions))
        ]

    (if all-transactions
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str all-transactions)
      ;;  :body (json/write-str (take-last 2000 all-transactions))
       }
      {:status 500
       :headers {"Content-Type" "text/html"}
       :body "all-transactions failed to complete: "})))

(defn transaction_details_handler [id request]
  (pp/pprint id)
  (let [tokens (auth/get_tokens "session_tokens.txt")
        transaction_details (retrieve_transaction_details tokens id)
        ]
    (if (nil? transaction_details)
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (str "retrieveing transaction details failed")}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (json/write-str transaction_details)}
      )))
