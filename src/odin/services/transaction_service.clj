(ns odin.services.transaction-service
  (:require [clojure.data.json :as json]
            [clojure.tools.trace :as trace]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            ;; [datomic.client.api :as d]
            ;; [odin.db :as db]
            [odin.db2 :as db2]
            [common.category-service :as category]
            [odin.services.date-service :as date]
            [odin.services.auth-service :as auth]
            [odin.services.account-service :as account-svc]
            [odin.services.merge-service :as merge]
            [clojure.test :as test :refer [deftest is do-report]]
            ;[odin.services.transaction-service :as transaction]
            ))


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
  (println "last-update-date last: " (last transactions))
  (println "last-update-date first: " (first transactions))
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

;; (defn retrieve-and-store-all-transactions [token account-key]
;;   (println "retrieve-and-store-all-transactions")
;;   (let [transactions (retrieve-bank-transactions-from "2022-05-01" token account-key)
;;         ;; enriched-transactions (enrich-transactions token transactions)
;;         tx-result (db/store-transactions transactions)]
;;     transactions))

(defn bank->internal-transaction [user-id new-transaction]
  {:user-id user-id
   :amount (-> new-transaction :amount str Double/parseDouble)
   :date (:date new-transaction)
   :date-index 0
   :description (:description new-transaction)
   :source new-transaction})

(defn add-date-index [index transaction]
  (assoc transaction :date-index index))

(defn index-date-group-transactions [new-transactions-date-group last-index]
  (->> new-transactions-date-group
       (map-indexed (fn [idx group] [(+ idx last-index) group]))
       (map #(apply add-date-index %))))

(defn add-last-index [date-indexes [date new-transactions-date-group]]
  ;(println "add-last-index: " date-indexes " " date " " new-transactions-date-group)
  (if (contains? date-indexes date)
    [new-transactions-date-group (-> date-indexes (get date) last inc)]
    [new-transactions-date-group 0]))

;; is updating date-index, should not convert external to internal data structure
(defn update-date-index-in-new-transactions [db-transactions new-transactions]
  (let [date-indexes (->> db-transactions
                          (group-by :date)
                          (map (fn [[a b]] [a (->> b (map :date-index) sort)]))
                          (into {}))
        add-last-index-fx (partial add-last-index date-indexes)
        updated-new-transactions (->> new-transactions
                                      (group-by :date)
                                      (map add-last-index-fx)
                                      (mapcat #(apply index-date-group-transactions %))
                                      (into []))]
        ;; (println "db-transactions: " (take 10 db-transactions))
        ;; (println "updated-new-transactions: " (take 10 updated-new-transactions))
    (->> updated-new-transactions
        (concat db-transactions)
        (sort-by :date))))



;; (update-date-index-in-new-transactions [{:date 1762729200000 :date-index 0 :amount 100} {:date 1762729200000 :date-index 1 :amount 200} {:date 1762729300000 :date-index 0 :amount 300}]
;;                             [{:date 1762729200000 :date-index 0 :amount 100 :description "asdf" :source {:date 1234}}])

(defn update-db-transaction [transaction updates-map]
  (let [key (str (:date transaction) "#" (:date-index transaction))]
    (if (contains? updates-map key)
      (merge transaction (get updates-map key))
      transaction)))

(defn update-db-transactions
  "merge db-transactions with updated verisons from the bank. Match on :date#:date-index string."
  [db-transactions updates]
  (let [m (->> updates
               (map (fn [u] [(str (:date u) "#" (:date-index u)) u]))
               (into {}))]
    (map #(update-db-transaction % m) db-transactions)))

; transactions have moved date, this implementation does not take that into account
; 1. add new transactions
; 2. update date-index
(defn add-updated-transactions [db-transactions updates]
  )

(defn transaction->sort-key [transaction]
  (let [date (:date transaction)
        date-index (:date-index transaction)]
    (str date "#" date-index)))

(defn remove-outdated-transactions [db-transactions old-transactions]
  (let [old-transactions-ids (->> old-transactions
                                  (map transaction->sort-key)
                                  (into #{}))]
    (filter #(not (contains? old-transactions-ids (transaction->sort-key %))) db-transactions)))

(defn old-transactions [transactions last-udpate-date]
  (let [last-unixtime (date/date->unixtime last-udpate-date)]
    (filter #(< (:date %) last-unixtime) transactions)))

(defn retrieve-all-transactions-year-by-year
  "Retrieve all transactions from bank, year by year, starting from now backwards until transactions are found"
  [token account-key]
  (loop [current-year (.getYear (java.time.ZonedDateTime/now))
         transactions []]
    (println "Retrieving transactions for year:" current-year)
    (let [from-date (date/iso-date->local-datetime (str current-year "-01-01"))
          to-date (date/iso-date->local-datetime (str current-year "-12-31"))
          year-transactions (retrieve-bank-transactions-from-to from-date to-date token account-key)]
      (println "Retrieved" (count year-transactions) "transactions for year" current-year)
      (if (and (empty? year-transactions) (< current-year 2015))
        ;; Stop if we reach 2020 and still no transactions (prevent infinite loop)
        transactions
        (if (empty? year-transactions)
          ;; No transactions for this year, continue to previous year
          (recur (dec current-year) transactions)
          ;; Found transactions, add them and continue to previous year
          (let [all-transactions (concat transactions year-transactions)]
            (recur (dec current-year) all-transactions)))))))

(defn trans->debug [trans]
  (->> trans
       (map #(select-keys % [:date :date-index :amount :category-id]))
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
(defn get-transactions2 [user-id token account-key]
  (let [categories (db2/get-categories user-id)
        first-date "2015-01-01"
        db-transactions (db2/get-transactions-after user-id first-date false)]

    (when (empty? db-transactions)
      (println "No transactions in database, retrieving all transactions from bank...")
      (let [all-bank-transactions (retrieve-all-transactions-year-by-year token account-key)
            _ (println "Total retrieved from bank:" (count all-bank-transactions))
            processed-transactions (->> all-bank-transactions
                                       (map (partial bank->internal-transaction user-id))
                                       (update-date-index-in-new-transactions [])
                                       (category/add-categories categories)
                                       (map replace-nil-description))
            _ (db2/store-transactions processed-transactions)]
        (println "Stored" (count processed-transactions) "transactions in database")))

    (let [db-transactions (db2/get-transactions-after user-id first-date true)
          _ (println "db-transactions: " (count db-transactions))
          last-update-date (last-update-date first-date db-transactions 14)
          latest-db-transactions (transactions-from db-transactions last-update-date)
          latest-bank-transactions (->> (retrieve-bank-transactions-from last-update-date token account-key)
                                         (map (partial bank->internal-transaction user-id)))
          ; date-index is added if match towards db-transaction is found, thus it is only added for updates
          ; new transactions will have the date-index added as a continium from last update
          ;; _ (println "latest-db-transactions: " (count latest-db-transactions))
          ;; _ (println "latest-bank-transactions: " (count latest-bank-transactions))
          [replacements, new] (merge/process-transactions-from-bank latest-db-transactions latest-bank-transactions)
          updates-and-new (-> latest-db-transactions
                              (remove-outdated-transactions (map :old replacements))
                              (update-date-index-in-new-transactions (concat (map :new replacements) new)))
          ; date will change for updates (transactions during weekend), how will that affect update-db-transactions? Will need to update date-indexs for updates as well
          ;; updated-db-transactions (update-db-transactions latest-db-transactions updates) ; should be removed; merge in updated versions based on date-indexs
          ;; updated-new (update-date-index-in-new-transactions updated-db-transactions new)
          ;; _ (println "get-transactions2: categories: " categories)
          categorized-updates-and-new (->> updates-and-new
                                           (category/add-categories categories)
                                           (map replace-nil-description))
          old (old-transactions db-transactions last-update-date)
          _ (db2/delete-transactions (map :old replacements))
          _ (db2/store-transactions categorized-updates-and-new)
          all-transactions (concat old categorized-updates-and-new)
          all-transactions-no-source (map #(dissoc % :source) all-transactions)]
          (println "Old: " (count old) " updates: " (count replacements) " new: " (count new))
          (println "latest-db-transactions: " (count latest-db-transactions))
          (println "latest-bank-transactions: " (count latest-bank-transactions))
          ;; (println  "last 10 updates-and-new: ")
          ;; (pp/pprint (take 20 (trans->debug updates-and-new)))
          ;; (println "categorized-updates-and-new: ")
          ;; (pp/pprint (take 20 (trans->debug categorized-updates-and-new)))
          ;; (println "all-transactions: ")
          ;; (pp/pprint (take 20 (trans->debug all-transactions)))
          ;; (println "done")
      all-transactions-no-source)))

(defn date->iso [iso-date]
  (-> iso-date
      date/iso-date-str->date
      date/date->unixtime))

(def db-transactions [{:amount 2.0 :date (date->iso "2025-11-10") :date-index 1 :description "cat"}
                      {:amount 3.0 :date (date->iso "2025-11-10") :date-index 2 :description "asdg" :category-id "1234"}
                      {:amount 3.0 :date (date->iso "2025-11-10") :date-index 3 :description "asdf"}])

(def bank-transactions [{:amount 2.0 :date (date->iso "2025-11-10") :description "cat" :source {:amount 2.0 :date (date->iso "2025-11-10") :description "cat"}}
                        {:amount 3.0 :date (date->iso "2025-11-10") :description "asdf" :source {:amount 3.0 :date (date->iso "2025-11-10") :description "asdf"}}
                        {:amount 3.0 :date (date->iso "2025-11-12") :description "asdf" :source {:amount 3.0 :date (date->iso "2025-11-12") :description "asdf"}}
                        {:amount 5.0 :date (date->iso "2025-11-12") :description "asdf" :source {:amount 5.0 :date (date->iso "2025-11-12") :description "asdf"}}
                        {:amount 6.0 :date (date->iso "2025-11-12") :description "asdf" :source {:amount 6.0 :date (date->iso "2025-11-12") :description "asdf"}}])

; updates have gotten new date and thus are not overwriting it self in db-transactions
; they need to store the old date and date-index in order to delete the old transaction

(let [[updates, new] (merge/process-transactions-from-bank db-transactions bank-transactions)
      updated-db-transactions (update-db-transactions db-transactions updates)
      ]
      updates
  ;; updated-db-transactions
  )

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


(defn get-all-transactions
  ([]
   (get-all-transactions "xxx"))
  ([user-id]
   (let [tokens (auth/get_tokens "session_tokens.txt")
         _ (println "retrieve accounts")
         accounts (retrieve_accounts tokens)
         body_str (-> accounts :body)
         body (json/read-str body_str :key-fn keyword)
         account_key (:key body)
         _ (println "account_key: " account_key)
         all-transactions (get-transactions2 user-id tokens account_key)]
     (pp/pprint (take 3 all-transactions)))))

(defn get-db-only-transactions
  "Return transactions from DB only, no bank fetching."
  [user-id]
  (db2/get-transactions-after user-id "2015-01-01" true))

(defn transaction_handler_test [req]
  {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str ["hello"])})

(defn- resolve-bank-account-key
  "Get the bank account key, fetching from the bank API and storing it if not yet known."
  [user-id account-id tokens existing-key]
  (if existing-key
    existing-key
    (do
      (println "Fetching bank account key from API...")
      (let [accounts-response (retrieve_accounts tokens)
            body (json/read-str (-> accounts-response :body) :key-fn keyword)
            bank-key (:key body)]
        (when bank-key
          (account-svc/store-bank-account-key user-id account-id bank-key))
        bank-key))))

(defn transaction_handler [request]
  (try
    (let [user-id (:user-id request)
          user-accounts (account-svc/get-user-accounts user-id)
          first-account (first user-accounts)]
      (if-not first-account
        (let [db-txns (get-db-only-transactions user-id)
              response-body (json/write-str db-txns)]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body response-body})
        (let [account-id (:account-id first-account)
              tokens (auth/get-tokens-for-account
                       #(account-svc/get-account-tokens user-id account-id)
                       #(account-svc/store-account-tokens user-id account-id %))]
          (if (nil? tokens)
            (let [db-txns (get-db-only-transactions user-id)
                  response-body (json/write-str db-txns)]
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body response-body})
            (let [account (db2/get-account user-id account-id)
                  account-key (resolve-bank-account-key user-id account-id tokens (:bank-account-key account))]
              (if (nil? account-key)
                (let [db-txns (get-db-only-transactions user-id)
                      response-body (json/write-str db-txns)]
                  {:status 200
                   :headers {"Content-Type" "application/json"}
                   :body response-body})
                (let [all-transactions (get-transactions2 user-id tokens account-key)
                      response-body (json/write-str all-transactions)]
                  (if all-transactions
                    {:status 200
                     :headers {"Content-Type" "application/json"
                               "Content-Length" (-> response-body .getBytes count str)}
                     :body response-body}
                    {:status 500
                     :headers {"Content-Type" "application/json"}
                     :body (json/write-str {:error "Failed to load transactions"})}))))))))
    (catch Exception e
      (println "transaction_handler error:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (str "Server error: " (.getMessage e))})})))

(defn transaction_details_handler [id request]
  (let [tokens (auth/get_tokens "session_tokens.txt")
        transaction_details (retrieve_transaction_details tokens id)]
    (if (nil? transaction_details)
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (str "retrieveing transaction details failed")}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (json/write-str transaction_details)})))
