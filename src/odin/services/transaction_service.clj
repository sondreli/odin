(ns odin.services.transaction-service
  (:require [clojure.data.json :as json]
            [clojure.tools.trace :as trace]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [clj-fuzzy.metrics :as fuzzy]
            ;; [datomic.client.api :as d]
            [odin.db :as db]
            [common.category-service :as category]
            [odin.services.auth-service :as auth]
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

(defn retrieve-transactions-from [date {token :access_token} account_key]
  (println date)
  (let [http-response (client/get "https://api.sparebank1.no/personal/banking/transactions"
                                  {;:accept "application/vnd.sparebank1.v1+json; charset=utf-8"
                                   :query-params {"accountKey" account_key
                                                  "fromDate" date}
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


(defn unixtime->localtime [unixtime]
  (let [zoneIdOslo (java.time.ZoneId/of "Europe/Oslo")
        inst (java.time.Instant/ofEpochMilli unixtime)
        offset (-> zoneIdOslo .getRules (.getOffset inst))
        local-now (.atOffset inst offset)]
  local-now))

(defn days-since-transaction [transaction]
  (let [;last-db-transaction (get-last-transaction db)
        now (java.time.ZonedDateTime/now)
        transaction-time (unixtime->localtime (:date transaction))
        time-diff (java.time.Duration/between transaction-time now)]
    (.toDays time-diff)))

(defn x-days-ago [days]
  (let [now (java.time.ZonedDateTime/now)
        x (.minus now days (java.time.temporal.ChronoUnit/DAYS))]
    x))

(defn subtract-days [date days]
  (let [;now (java.time.ZonedDateTime/now)
        x (.minus date days (java.time.temporal.ChronoUnit/DAYS))]
    x))

(defn find-retrieval-date [transaction]
  (let [days-since-last-db-transaction (days-since-transaction transaction)]
    (println days-since-last-db-transaction)
    (if (< 7 days-since-last-db-transaction)
      ;; (x-days-ago days-since-last-db-transaction)
      (unixtime->localtime (:date transaction))
      (x-days-ago 7))))

(defn filter-out-overlapping-transactions [last-10-transaction-in-db transactions-from-bank]
  (if (-> transactions-from-bank count (= 0))
    []
    (let [first-10-bank-amounts (->> transactions-from-bank (take 10) (map #(-> % :amount double)))
          last-10-db-amounts (map :amount last-10-transaction-in-db)
          same? (->> (map vector last-10-db-amounts first-10-bank-amounts)
                     (map #(apply = %))
                     (every? identity))]
      (println last-10-db-amounts)
      (println first-10-bank-amounts)
      (println same?)
      (if same?
        (drop (count last-10-db-amounts) transactions-from-bank)
        (filter-out-overlapping-transactions last-10-transaction-in-db
                                             (rest transactions-from-bank))))))

;; (def a [{:amount 1} {:amount 2} {:amount 3} {:amount 4}])
;; (def b [{:amount 1} {:amount 2} {:amount 3} {:amount 4} {:amount 5}])
;; (filter-out-overlapping-transactions a b)

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


(let [start-date (-> 1703631600000 unixtime->localtime (subtract-days 14))
      trans-date (-> 1703631600000 unixtime->localtime)
      ]
  (.isEqual start-date trans-date))

(defn transactions-from [transactions date]
  (let [isOnOrAfter (fn [date-a date-b] (or (.isAfter date-a date-b)
                                             (.isEqual date-a date-b)))]
    (filter #(-> % :date unixtime->localtime (isOnOrAfter date)) transactions)))

; 1 always have amount-found
; 2 first check for same date
; 3 if not no more than 3 days moved?
; if multiple alternatives take the one with the lowest levenshtein distance
; and maybe a max distance
; instead of returning bool. should return 
; discard, keep, replaces{this other transaction}
; should also mark or remove matches from lookup-map
; 1 -> keep or next
; 2 -> discard or next
; 3 -> replace or keep
; a function for each of the three that returns their states
; and a recursive function that applies them all according to returned states and returns the final state
; no need, is just the amount in trans ; when next, the same hit in the lookup-map is beeing used and should be passed along
; discard and replace are matches and should remove that transaction from the lookup-map

(defn check-amount
  "keep the transaction if the amount is not found"
  [lookup-map transaction]
  (if (->> transaction :amount double (contains? lookup-map))
    [:next lookup-map]
    [:keep lookup-map]))

(defn remove-match
  "will discard this transaction and return a updated lookup-map"
  [lookup-map amount match]
  (let [have-same-index? (fn [t] (-> t :index (= (:index match))))
        updated-lookup-map (update-in lookup-map [amount] #(remove have-same-index? %))] ; remove the matched transaction in the lookup-map
    updated-lookup-map))

(defn check-same-date
  "discard the transaction if we have one on the same date"
  [lookup-map transaction]
  (let [amount (-> transaction :amount double)
        match (->> (get lookup-map amount)
                   (filter #(-> transaction :date unixtime->localtime
                                (.isEqual (-> % :date unixtime->localtime))))
                   (map #(assoc % :levenshtein (-> % :description (fuzzy/levenshtein (:description transaction)))))
                   (sort-by :levenshtein)
                  ;;  (filter #(< (:levenshtein %) 10)) ; should compare the levenshtein in relation to the longest desc agains a threshold
                   first)]
    (println "check-same-date match: " match)
    (when (and (some? match) (-> match :levenshtein (> 0)))
      (println (:description transaction)))
    ;; (if (some? match)
    ;;   [:discard (remove-match lookup-map amount match)]
    ;;   [:next lookup-map])
    (cond
      (and (some? match) (-> match :levenshtein (> 0)))
      [:replace (remove-match lookup-map amount match) (:db-id match)]
      (some? match)
      [:discard (remove-match lookup-map amount match)]
      :else
      [:next lookup-map])
    ))

(defn days-between [trans1 trans2]
  (let [time1 (-> trans1 :date unixtime->localtime)
        time2 (-> trans2 :date unixtime->localtime)]
    (.toDays (java.time.Duration/between time1 time2))))

(defn check-close-date
  "keep or make this trans replace another if it match or not a close date"
  [lookup-map transaction]
  (let [amount (-> transaction :amount double)
        match (->> (get lookup-map amount)
                   (filter #(let [days (days-between % transaction)]
                              (and (> days 0) (< days 8))))
                   (map #(assoc % :levenshtein (-> % :description (fuzzy/levenshtein (:description transaction)))))
                   (sort-by :levenshtein)
                  ;;  (filter #(< (:levenshtein %) 10)) ; should compare the levenshtein in relation to the longest desc agains a threshold
                   first)]
    (println "check-close-date: " match)
    (if (some? match)
      [:replace (remove-match lookup-map amount match) (:db-id match)]
      [:keep lookup-map])))

(defn map-to-action [lookup-map transaction]
  (loop [action-funcs [check-amount check-same-date check-close-date]]
    (let [[action new-lookup-map db-id] (apply (first action-funcs) [lookup-map transaction])]
      (if (= action :next)
        (recur (rest action-funcs))
        [action new-lookup-map db-id]))))

(defn transaction-not-in-lookup-map? [lookup-map transaction]
  (let [amount-found? (->> transaction :amount double (contains? lookup-map))
        same-date? (some #(-> transaction :date unixtime->localtime
                              (.isEqual (-> % :date unixtime->localtime)))
                         (->> transaction :amount double (get lookup-map)))
        ;TODO same-desc? ()
        result (not (and amount-found? same-date?))
        ]
    (println (:amount transaction) result " amount-found: " amount-found? " same-date: " same-date?)
    result))

(defn map-to-actions [lookup-map transactions]
  (if (-> transactions count (= 0))
    '()
    (let [trans (first transactions)
          [action-key next-lookup-map db-id] (map-to-action lookup-map trans)
          action (if (some? db-id)
                   {:action action-key :db-id db-id}
                   {:action action-key})]
    (conj (map-to-actions next-lookup-map (rest transactions)) action))))

(defn add-data-to-replacement [transactions-in-db partial-transaction] 
  (let [have-same-db-id? (fn [t] (-> t :db-id (= (:db-id partial-transaction))))
        category (some #(when (have-same-db-id? %) (:category %)) transactions-in-db)]
    (-> partial-transaction
        (assoc :amount (-> partial-transaction :source :amount))
        (assoc :description (-> partial-transaction :source :description))
        (assoc :date (-> partial-transaction :source :date))
        (db/assoc-attribute [:category category]))))

(defn add-data-to-new [[idx new-transaction]]
  (let [trans {:temp-id (str idx)
               :amount (:amount new-transaction)
               :date (:date new-transaction)
               :description (:description new-transaction)
               :source new-transaction
               :category nil}]
    (reduce db/assoc-attribute {} trans)))

(defn process-transactions-from-bank [transactions-in-db transactions-from-bank]
  ;; (println  transactions-in-db)
  (let [lookup-map (->> transactions-in-db
                        (map (juxt :amount #(select-keys % [:date :description :db-id])))
                        (reduce (fn [acc [k v]] (let [list (get acc k)
                                                      v2 (assoc v :index (count list))]
                                                  (assoc acc k (conj list v2)))) {}))
        _ (println "lookup-map: " lookup-map)
        actions (map-to-actions lookup-map transactions-from-bank)
        _ (println (into [] actions))
        ;; _ (println transactions-from-bank)
        replacements (->> (map vector actions transactions-from-bank)
                          (filter (fn [[{action :action} _]] (= action :replace)))
                          (map (fn [[{db-id :db-id} trans]] {:db-id db-id :source trans}))
                          (map #(add-data-to-replacement transactions-in-db %)))
        new-trans (->> (map vector actions transactions-from-bank)
                       (filter (fn [[{action :action} _]] (= action :keep)))
                       (map-indexed (fn [idx [_ trans]] [idx trans]))
                       (map add-data-to-new))]
    [replacements new-trans]))

(process-transactions-from-bank
 [{:amount 2.0 :date 1703631600000 :db-id 1 :description "cat"}
  {:amount 3.0 :date 1703631600000 :db-id 2 :description "asdg" :category {:color "#aff"}}
  {:amount 3.0 :date 1703631600000 :db-id 3 :description "asdf"}
  ]
 [{:amount 2.0 :date 1703631600000 :description "cat"}
  {:amount 3.0 :date 1703631600000 :description "asdf"}
  {:amount 3.0 :date 1703718000000 :description "asdf"}
  {:amount 5.0 :date 1703718000000 :description "asdf"}
  {:amount 6.0 :date 1703718000000 :description "asdf"}])

(defn replace-nil-description [transaction]
  (if (-> transaction :description some?)
    transaction
    (let [destination-account (-> transaction :source :remoteAccountNumber)
          desc (str "Overføring til: " destination-account)]
      (assoc transaction :description desc))))

(defn append-new-transactions2 [transactions-in-db token account-key]
  (let [days-back 14
        last-date-in-db (-> transactions-in-db last :date unixtime->localtime)
        start-date (-> last-date-in-db (subtract-days days-back))
        start-date-str (-> start-date .toLocalDate str)
        latest-transactions-in-db (transactions-from transactions-in-db start-date)
        _ (println "latest-transactions-in-db: " (count latest-transactions-in-db))
        latest-transactions-from-bank (retrieve-transactions-from start-date-str token account-key)
        [replace-transactions
         new-transactions] (process-transactions-from-bank latest-transactions-in-db
                                                           latest-transactions-from-bank)
        categories (db/get-categories)
        categorized-new-transactions (->> new-transactions
                                          (category/add-categories categories)
                                          (map replace-nil-description))
        categorized-replace-transactions (->> replace-transactions
                                              (category/add-categories categories)
                                              (map replace-nil-description))]
    (println "append-new-transactions2: " (count transactions-in-db) (count new-transactions))
    (println "categorized-replace-transactions: " categorized-replace-transactions)
    ; find the old one, already have it in retrieved from db
    ; move it's category over to new transaction
    ; create the :amount and :description keys
    ; now you can delete the old entry
    ; and add the new one
    ; and return the new transactions as part of all transactions to frontend
    (db/replace-transactions categorized-replace-transactions) ; store and return with db-id
    (db/store-transactions2 categorized-new-transactions) ;; should categorize before store and return the trans with db-id
    ;(concat transactions-in-db categorized-new-transactions) ; transactions-in-db are outdated after replace-transactions
    (db/get-transactions)
    ;; should sort here
    ))

(defn append-new-transactions [transactions-in-db token account-key]
  (println "append-new-transactions: " (count transactions-in-db))
  (let [last-10-transactions-in-db (take-last 10 transactions-in-db)
        _ (println (first last-10-transactions-in-db))
        first-date (-> last-10-transactions-in-db first find-retrieval-date .toLocalDate str)
        lates-transactions-from-bank (retrieve-transactions-from first-date token account-key)
        new-transactions (filter-out-overlapping-transactions last-10-transactions-in-db
                                                              lates-transactions-from-bank)
        ;; enriched-new-transactions (enrich-transactions token new-transactions)
        ]
    ;; (store-transactions db)
    (println (str "last-10-transactions-in-db: " last-10-transactions-in-db))
    (println (str "count latest-transactions-from-bank: " (count lates-transactions-from-bank)))
    ;; (println (str "lates-transatctions-from-bank: " (pr-str (map :amount lates-transactions-from-bank))))
    ;; (println (str "lates-transatctions-from-bank: " (pr-str (map #(-> % :amount type) lates-transactions-from-bank))))
    (println (str "count new-transactions: " (count new-transactions)))
    ;; (println (str "count enriched-new-transactions: " (count enriched-new-transactions)))
    (concat transactions-in-db new-transactions)))

(defn retrieve-and-store-all-transactions [token account-key]
  (println "retrieve-and-store-all-transactions")
  (let [transactions (retrieve-transactions-from "2022-05-01" token account-key)
        ;; enriched-transactions (enrich-transactions token transactions)
        tx-result (db/store-transactions transactions)]
    transactions))

(defn get-transactions [token account-key]
  (println "get-transactions: " (count (db/get-transactions)))
  (if-let [transactions-in-db (not-empty (db/get-transactions))]
    (append-new-transactions2 transactions-in-db token account-key)
    (retrieve-and-store-all-transactions token account-key)))

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
        ;; transactions_response (retrieve_transactions tokens account_key)
        ;; all_transactions (:transactions (json/read-str (:body transactions_response) :key-fn keyword))
        all-transactions (get-transactions tokens account_key)
        transactions (filter_transactions all-transactions :description "google")]

    (pp/pprint accounts)
    (pp/pprint (take 1 all-transactions))
    (pp/pprint transactions)
    (println account_key)))

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
        ;; transactions_response (retrieve-transactions-from "2022-05-20" tokens account_key)
        ;; all_transactions (:transactions (json/read-str (:body transactions_response) :key-fn keyword))
        all-transactions (get-transactions tokens account_key)
        _ (println (take 3 all-transactions))]

    (if all-transactions
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (json/write-str all-transactions)}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Second respone"})))

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
