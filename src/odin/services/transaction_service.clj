(ns odin.services.transaction-service
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [odin.db2 :as db2]
            [common.category-service :as category]
            [common.loan-service :as loan-svc]
            [odin.services.date-service :as date]
            [odin.services.auth-service :as auth]
            [odin.services.account-service :as account-svc]
            [odin.services.merge-service :as merge]
            [odin.services.http-service :as http]
            [odin.services.loan-service :as loans]
            [odin.services.category-service :as category-svc]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayOutputStream]
           [java.time Instant]))

(defn- extend-loan-histories!
  "For each of the user's loans whose filters match anything in the supplied
   delta-txns, run the incremental extend-payment-history and write back when
   the history actually changed. Each loan is processed independently; a failure
   on one loan logs but does not abort the others or the surrounding request."
  [user-id delta-txns]
  (try
    (doseq [loan (db2/get-loans user-id)]
      (try
        (when-let [matching-delta (loans/matching-txns-for-loan loan delta-txns)]
          (when (seq matching-delta)
            (let [extended (loan-svc/extend-payment-history loan matching-delta)]
              (when (not= (:payment-history loan) (:payment-history extended))
                (println "Loan" (:id loan) "history extended; storing.")
                (db2/store-loan (assoc extended :user-id user-id))))))
        (catch Exception e
          (println "Failed to extend history for loan" (:id loan) ":" (.getMessage e)))))
    (catch Exception e
      (println "Failed to load loans for history extension:" (.getMessage e)))))


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
  (let [date-str (-> ^java.time.OffsetDateTime date .toLocalDate str)
        http-response (http/http-get "https://api.sparebank1.no/personal/banking/transactions"
                                     {:query-params {"accountKey" account_key
                                                     "fromDate" date-str}
                                      :headers {:authorization (str "Bearer " token)
                                                :accept "application/vnd.sparebank1.v1+json; charset=utf-8"}})
        transactions (-> http-response extract-body read-json extract-transactions)]
    transactions))

(defn retrieve-bank-transactions-from-to [from-date to-date {token :access_token} account_key]
  (let [from-date-str (-> ^java.time.OffsetDateTime from-date .toLocalDate str)
        to-date-str (-> ^java.time.OffsetDateTime to-date .toLocalDate str)
        http-response (http/http-get "https://api.sparebank1.no/personal/banking/transactions"
                                     {:query-params {"accountKey" account_key
                                                     "fromDate" from-date-str
                                                     "toDate" to-date-str}
                                      :headers {:authorization (str "Bearer " token)
                                                :accept "application/vnd.sparebank1.v1+json; charset=utf-8"}})
        transactions (-> http-response extract-body read-json extract-transactions)]
    transactions))

(defn retrieve_accounts [{token :access_token}]
  (http/http-get "https://api.sparebank1.no/personal/banking/accounts/default"
                 {:headers {:authorization (str "Bearer " token)}}))

(defn retrieve_accounts_list
  "List all accounts the user has access to. Each account in the response includes
   balance and availableBalance fields."
  [{token :access_token}]
  (http/http-get "https://api.sparebank1.no/personal/banking/accounts"
                 {:headers {:authorization (str "Bearer " token)
                            :accept "application/vnd.sparebank1.v1+json; charset=utf-8"}}))


(defn retrieve_transaction_details [{token :access_token} transaction_id]
  (println "retrieveing trans details")
  (let [http-response (http/http-get (str "https://api.sparebank1.no/personal/banking/transactions/" transaction_id "/details")
                                     {:headers {:authorization (str "Bearer " token)
                                                :accept "application/vnd.sparebank1.v1+json; charset=utf-8"}})
        transaction (-> http-response extract-body read-json)]
    transaction))

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


(comment
  (let [start-date (-> 1703631600000 date/unixtime->localtime (date/subtract-days 14))
        trans-date (-> 1703631600000 date/unixtime->localtime)]
    (.isEqual start-date trans-date)))

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

(defn bank->internal-transaction [user-id account-id new-transaction]
  {:user-id user-id
   :account-id account-id
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
  (loop [current-year (.getYear ^java.time.ZonedDateTime (java.time.ZonedDateTime/now))
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
(defn get-transactions2 [user-id account-id token account-key]
  (let [categories (category-svc/get-categories-with-filters user-id)
        first-date "2015-01-01"
        db-transactions (db2/get-transactions-after user-id first-date false)]

    (when (empty? db-transactions)
      (println "No transactions in database, retrieving all transactions from bank...")
      (let [all-bank-transactions (retrieve-all-transactions-year-by-year token account-key)
            _ (println "Total retrieved from bank:" (count all-bank-transactions))
            processed-transactions (->> all-bank-transactions
                                       (map (partial bank->internal-transaction user-id account-id))
                                       (update-date-index-in-new-transactions [])
                                       (category/add-categories categories)
                                       (map replace-nil-description))
            _ (db2/store-transactions processed-transactions)]
        (println "Stored" (count processed-transactions) "transactions in database")
        (extend-loan-histories! user-id processed-transactions)))

    (let [db-transactions (db2/get-transactions-after user-id first-date true)
          _ (println "db-transactions: " (count db-transactions))
          last-update-date (last-update-date first-date db-transactions 14)
          latest-db-transactions (transactions-from db-transactions last-update-date)
          latest-bank-transactions (->> (retrieve-bank-transactions-from last-update-date token account-key)
                                         (map (partial bank->internal-transaction user-id account-id)))
          ; date-index is added if match towards db-transaction is found, thus it is only added for updates
          ; new transactions will have the date-index added as a continium from last update
          ;; _ (println "latest-db-transactions: " (count latest-db-transactions))
          ;; _ (println "latest-bank-transactions: " (count latest-bank-transactions))
          [replacements, new, orphans] (merge/process-transactions-from-bank latest-db-transactions latest-bank-transactions)
          updates-and-new (-> latest-db-transactions
                              (remove-outdated-transactions (concat (map :old replacements) orphans))
                              (update-date-index-in-new-transactions (concat (map :new replacements) new)))
          ; date will change for updates (transactions during weekend), how will that affect update-db-transactions? Will need to update date-indexs for updates as well
          ;; updated-db-transactions (update-db-transactions latest-db-transactions updates) ; should be removed; merge in updated versions based on date-indexs
          ;; updated-new (update-date-index-in-new-transactions updated-db-transactions new)
          ;; _ (println "get-transactions2: categories: " categories)
          categorized-updates-and-new (->> updates-and-new
                                           (category/add-categories categories)
                                           (map replace-nil-description))
          old (old-transactions db-transactions last-update-date)
          _ (db2/delete-transactions (concat (map :old replacements) orphans))
          _ (db2/store-transactions categorized-updates-and-new)
          all-transactions (concat old categorized-updates-and-new)
          all-transactions-no-source (map #(dissoc % :source) all-transactions)
          _ (extend-loan-histories! user-id categorized-updates-and-new)]
          (when (seq orphans)
            (println "Orphans removed (db transactions with no bank match):" (count orphans) orphans))
          (println "Refresh result - old:" (count old) "updates:" (count replacements) "new:" (count new) "orphans-removed:" (count orphans))
      {:transactions all-transactions-no-source
       :new-count (count new)
       :updated-count (count replacements)})))

(defn date->iso [iso-date]
  (-> iso-date
      date/iso-date-str->date
      date/date->unixtime))

(comment
  (def db-transactions [{:amount 2.0 :date (date->iso "2025-11-10") :date-index 1 :description "cat"}
                        {:amount 3.0 :date (date->iso "2025-11-10") :date-index 2 :description "asdg" :category-id "1234"}
                        {:amount 3.0 :date (date->iso "2025-11-10") :date-index 3 :description "asdf"}])

  (def bank-transactions [{:amount 2.0 :date (date->iso "2025-11-10") :description "cat" :source {:amount 2.0 :date (date->iso "2025-11-10") :description "cat"}}
                          {:amount 3.0 :date (date->iso "2025-11-10") :description "asdf" :source {:amount 3.0 :date (date->iso "2025-11-10") :description "asdf"}}
                          {:amount 3.0 :date (date->iso "2025-11-12") :description "asdf" :source {:amount 3.0 :date (date->iso "2025-11-12") :description "asdf"}}
                          {:amount 5.0 :date (date->iso "2025-11-12") :description "asdf" :source {:amount 5.0 :date (date->iso "2025-11-12") :description "asdf"}}
                          {:amount 6.0 :date (date->iso "2025-11-12") :description "asdf" :source {:amount 6.0 :date (date->iso "2025-11-12") :description "asdf"}}])

  (let [[updates, new] (merge/process-transactions-from-bank db-transactions bank-transactions)
        updated-db-transactions (update-db-transactions db-transactions updates)]
    updates))

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
         all-transactions (get-transactions2 user-id nil tokens account_key)]
     (pp/pprint (take 3 all-transactions)))))

(defn write-transit [data]
  (let [out (ByteArrayOutputStream.)
        writer (transit/writer out :json)]
    (transit/write writer data)
    (.toString out "UTF-8")))

(defn transit-response [data]
  {:status 200
   :headers {"Content-Type" "application/transit+json"}
   :body (write-transit data)})

(defn get-db-only-transactions
  "Return transactions from DB only, no bank fetching."
  [user-id]
  (db2/get-transactions-after user-id "2015-01-01" true))

(defn get-recent-transactions
  "Return the most recent 2 months of transactions from DB."
  [user-id]
  (if-let [latest-date-str (db2/get-latest-transaction-date user-id)]
    (let [latest-date (java.time.LocalDate/parse latest-date-str)
          two-months-before (.minusMonths latest-date 2)
          iso-date (str two-months-before)]
      (println "Recent transactions: latest=" latest-date-str "from=" iso-date)
      (db2/get-transactions-after user-id iso-date true))
    []))

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

(defn account-tokens-result
  "Obtain valid bank tokens for `account`. Returns a map:
     {:tokens t}                  on success
     {:tokens nil :reauth true}   when the refresh token has expired and the user
                                  must re-authenticate (PSD2 SCA — cannot be automated)
     {:tokens nil}                on any other (transient) failure."
  [user-id account]
  (let [account-id (:account-id account)]
    (try
      {:tokens (auth/get-tokens-for-account
                 (:client-id account)
                 (:client-secret account)
                 #(account-svc/get-account-tokens user-id account-id)
                 #(account-svc/store-account-tokens user-id account-id %)
                 account-id)}
      (catch clojure.lang.ExceptionInfo e
        (if (= :token-expired (:type (ex-data e)))
          (do (println "Bank refresh token expired for account" account-id "- re-auth required")
              {:tokens nil :reauth true})
          (do (println "Bank token refresh failed:" (.getMessage e))
              {:tokens nil})))
      (catch Exception e
        (println "Bank token refresh failed:" (.getMessage e))
        {:tokens nil}))))

(defn transaction_handler [request]
  (try
    (let [user-id (:user-id request)
          user-accounts (db2/get-accounts-for-user user-id)
          ;; Only OAuth/bank accounts can sync over an API; CSV-imported accounts (e.g. Nordnet) are skipped.
          first-account (->> user-accounts
                             (remove #(= :csv (:type (account-svc/get-provider (:provider %)))))
                             first)
          db-only-response (fn [extra]
                             (transit-response (merge {:transactions (get-db-only-transactions user-id)
                                                       :new-count 0 :updated-count 0}
                                                      extra)))]
      (if-not first-account
        (db-only-response nil)
        (let [account-id (:account-id first-account)
              {:keys [tokens reauth]} (account-tokens-result user-id first-account)]
          (if (nil? tokens)
            (db-only-response (when reauth {:reauth-required true :reauth-account-id account-id}))
            (let [account (db2/get-account user-id account-id)
                  account-key (resolve-bank-account-key user-id account-id tokens (:bank-account-key account))]
              (if (nil? account-key)
                (db-only-response nil)
                (let [result (get-transactions2 user-id account-id tokens account-key)]
                  (if result
                    (do
                      (try
                        (account-svc/store-account-last-sync
                          user-id account-id (str (Instant/now)))
                        (catch Exception e
                          (println "store-account-last-sync failed:" (.getMessage e))))
                      (transit-response result))
                    {:status 500
                     :headers {"Content-Type" "application/json"}
                     :body (json/write-str {:error "Failed to load transactions"})}))))))))
    (catch Exception e
      (println "transaction_handler error:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (str "Server error: " (.getMessage e))})})))

(defn recent_transaction_handler [request]
  (try
    (let [user-id (:user-id request)
          transactions (get-recent-transactions user-id)]
      (transit-response {:transactions transactions :new-count 0 :updated-count 0}))
    (catch Exception e
      (println "recent_transaction_handler error:" (.getMessage e))
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (str "Server error: " (.getMessage e))})})))

(defn investment-transactions-handler
  "Return the user's raw investment (Nordnet) transactions for listing on the
   transactions page. The client maps them into the table's display shape."
  [request]
  (try
    (let [user-id (:user-id request)
          transactions (db2/get-investment-transactions user-id)]
      (transit-response {:transactions transactions}))
    (catch Exception e
      (println "investment-transactions-handler error:" (.getMessage e))
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (str "Server error: " (.getMessage e))})})))

(defn retrieve-balance
  "Fetch the balance of the user's bank account. Calls the accounts-list endpoint
   and picks the account whose key matches `account-key` (or the first one if not
   supplied). Returns {:balance N :available-balance N} or nil on failure."
  [tokens account-key]
  (let [response (retrieve_accounts_list tokens)
        status (:status response)
        body-str (:body response)]
    (cond
      (not (and status (<= 200 status 299)))
      (do
        (println "retrieve-balance: bank returned status" status
                 "body:" (some-> body-str (subs 0 (min 200 (count body-str)))))
        nil)

      (nil? body-str)
      (do (println "retrieve-balance: empty body") nil)

      :else
      (let [body (try (json/read-str body-str :key-fn keyword)
                      (catch Exception e
                        (println "retrieve-balance: parse failed:" (.getMessage e))
                        nil))
            accounts (:accounts body)
            matched (or (when account-key
                          (some #(when (= (:key %) account-key) %) accounts))
                        (first accounts))]
        (cond
          (nil? body)
          (do (println "retrieve-balance: body parse returned nil") nil)

          (nil? matched)
          (do (println "retrieve-balance: no matching account — body keys:" (keys body)
                       "account count:" (count accounts))
              nil)

          :else
          {:balance (:balance matched)
           :available-balance (:availableBalance matched)
           :account-number (or (:accountNumber matched) (:formattedNumber matched))})))))

(defn- with-tokens
  "Best-effort: load + refresh tokens for an account. Returns the tokens map or nil."
  [user-id account]
  (try
    (auth/get-tokens-for-account
      (:client-id account)
      (:client-secret account)
      #(account-svc/get-account-tokens user-id (:account-id account))
      #(account-svc/store-account-tokens user-id (:account-id account) %)
      (:account-id account))
    (catch Exception e
      (println "with-tokens: bank token refresh failed:" (.getMessage e))
      nil)))

(defn- enrich-account-with-bank
  "Decorate a stored account with live :balance + :account-number from the bank.
   Persists :account-number if newly seen. On any failure, returns the input
   account unchanged. Skips non-OAuth providers (CSV, grocery)."
  [user-id account]
  (let [has-cred? (account-svc/has-credentials? account)
        base (-> account
                 (dissoc :client-id :client-secret :redirect-uri :token-data)
                 (assoc :has-credentials has-cred?))
        ptype (:type (account-svc/get-provider (:provider account)))]
    (if (not= :oauth ptype)
      base
      (if-let [tokens (with-tokens user-id account)]
        (if-let [info (retrieve-balance tokens (:bank-account-key account))]
          (let [new-num (:account-number info)]
            (when (and new-num (not= new-num (:account-number account)))
              (try (account-svc/store-account-number user-id (:account-id account) new-num)
                   (catch Exception e
                     (println "enrich-account-with-bank: store-account-number failed:"
                              (.getMessage e)))))
            (cond-> base
              true                       (assoc :balance (or (:available-balance info)
                                                             (:balance info)))
              (:account-number info)     (assoc :account-number (:account-number info))))
          base)
        base))))

(defn accounts-handler
  "Return user accounts decorated with live :balance / :account-number from the bank
   plus the stored :last-sync timestamp."
  [request]
  (try
    (let [user-id (:user-id request)
          accounts (db2/get-accounts-for-user user-id)
          enriched (mapv #(enrich-account-with-bank user-id %) accounts)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str enriched)})
    (catch Exception e
      (println "accounts-handler error:" (.getMessage e))
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (.getMessage e)})})))

(defn balance_handler [request]
  (try
    (let [user-id (:user-id request)
          user-accounts (db2/get-accounts-for-user user-id)
          ;; Only OAuth/bank accounts have a live balance; CSV/grocery accounts are skipped.
          first-account (->> user-accounts
                             (filter #(= :oauth (:type (account-svc/get-provider (:provider %)))))
                             first)]
      (if-not first-account
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:balance nil})}
        (let [account-id (:account-id first-account)
              {:keys [tokens reauth]} (account-tokens-result user-id first-account)]
          (if (nil? tokens)
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body (json/write-str (cond-> {:balance nil}
                                     reauth (assoc :reauth-required true
                                                   :reauth-account-id account-id)))}
            (let [balance (retrieve-balance tokens (:bank-account-key first-account))]
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body (json/write-str (or balance {:balance nil}))})))))
    (catch Exception e
      (println "balance_handler error:" (.getMessage e))
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:balance nil})})))

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
