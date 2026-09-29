(ns odin.services.merge-service
  (:require [odin.services.date-service :as date]
            [clj-fuzzy.metrics :as fuzzy]
            ))

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

(defn assoc-attribute [mymap [attr value]]
  (if (nil? value)
    mymap
    (assoc mymap attr value)))

(def amount-tolerance 0.10)            ; kr; treat amounts within ±0.10 as the same
(def description-similarity-max 0.34)  ; max normalized levenshtein for a near-amount match (tunable)

(defn candidates-within-tolerance
  "All lookup-map entries whose amount key is within amount-tolerance of target,
   each tagged with its source :amount so it can be removed from the correct bucket."
  [lookup-map target-amount]
  (->> lookup-map
       (filter (fn [[amt _]] (<= (Math/abs (- (double amt) target-amount)) amount-tolerance)))
       (mapcat (fn [[amt entries]] (map #(assoc % :amount amt) entries)))))

(defn description-similar?
  "True when descriptions are close enough to be the same merchant.
   Always true when amounts are exactly equal (preserves prior exact-match behavior)."
  [candidate-amount target-amount desc-a desc-b]
  (or (== (double candidate-amount) (double target-amount))
      (let [d       (fuzzy/levenshtein (str desc-a) (str desc-b))
            longest (max 1 (count (str desc-a)) (count (str desc-b)))]
        (<= (/ (double d) longest) description-similarity-max))))

(defn check-amount
  "keep the transaction if no candidate within amount-tolerance is found"
  [lookup-map transaction]
  (if (seq (candidates-within-tolerance lookup-map (-> transaction :amount double)))
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
        match (->> (candidates-within-tolerance lookup-map amount)
                   (filter #(-> transaction :date date/unixtime->localtime
                                (.isEqual (-> % :date date/unixtime->localtime))))
                   (filter #(description-similar? (:amount %) amount (:description %) (:description transaction)))
                   (map #(assoc % :levenshtein (-> % :description (fuzzy/levenshtein (:description transaction)))))
                   (sort-by :levenshtein)
                   first)]
    (cond
      (nil? match)
      [:next lookup-map]
      ;; identical description AND identical amount → a true duplicate, drop it
      (and (zero? (:levenshtein match)) (== (double (:amount match)) amount))
      [:discard (remove-match lookup-map (:amount match) match)]
      ;; otherwise (differing description and/or near amount) the bank version supersedes the db one
      :else
      [:replace (remove-match lookup-map (:amount match) match) (select-keys match [:date :date-index :category-id])])))

(defn days-between [trans1 trans2]
  (let [time1 (-> trans1 :date date/unixtime->localtime)
        time2 (-> trans2 :date date/unixtime->localtime)]
    (.toDays (java.time.Duration/between time1 time2))))

(defn check-close-date
  "keep or make this trans replace another if it match or not a close date"
  [lookup-map transaction]
  (let [amount (-> transaction :amount double)
        match (->> (candidates-within-tolerance lookup-map amount)
                   (filter #(let [days (days-between % transaction)]
                              (and (> days 0) (< days 8))))
                   (filter #(description-similar? (:amount %) amount (:description %) (:description transaction)))
                   (map #(assoc % :levenshtein (-> % :description (fuzzy/levenshtein (:description transaction)))))
                   (sort-by :levenshtein)
                   first)]
    (if (some? match)
      [:replace (remove-match lookup-map (:amount match) match) (select-keys match [:date :date-index :category-id])]
      [:keep lookup-map])))

(defn map-to-action [lookup-map transaction]
  (loop [action-funcs [check-amount check-same-date check-close-date]]
    (let [[action new-lookup-map date-index] (apply (first action-funcs) [lookup-map transaction])]
      (if (= action :next)
        (recur (rest action-funcs))
        [action new-lookup-map date-index]))))

(defn transaction-not-in-lookup-map? [lookup-map transaction]
  (let [amount-found? (->> transaction :amount double (contains? lookup-map))
        same-date? (some #(-> transaction :date date/unixtime->localtime
                              (.isEqual (-> % :date date/unixtime->localtime)))
                         (->> transaction :amount double (get lookup-map)))
        ;TODO same-desc? ()
        result (not (and amount-found? same-date?))
        ]
    ;; (println (:amount transaction) result " amount-found: " amount-found? " same-date: " same-date?)
    result))

(defn map-to-actions
  "Returns {:actions [...] :lookup-map final} where actions matches transactions
   positionally and lookup-map holds the db entries left unmatched."
  [lookup-map transactions]
  (reduce (fn [acc trans]
            (let [[action-key next-map db-match] (map-to-action (:lookup-map acc) trans)
                  action (cond-> {:action action-key}
                           (some? db-match) (assoc :db-match db-match))]
              {:actions (conj (:actions acc) action) :lookup-map next-map}))
          {:actions [] :lookup-map lookup-map}
          transactions))

(defn add-data-to-replacement [transactions-in-db partial-transaction] 
  (let [have-same-db-id? (fn [t] (-> t :db-id (= (:db-id partial-transaction))))
        category-id (some #(when (have-same-db-id? %) (:category-id %)) transactions-in-db)] ; this can not be efficent
    (-> partial-transaction
        (assoc :amount (-> partial-transaction :source :amount))
        (assoc :description (-> partial-transaction :source :description))
        (assoc :date (-> partial-transaction :source :date))
        (assoc-attribute [:category-id category-id]))))

; this one need to return replacements
; {:old {:date :date-index} :new {with all data}}
; the old transaction then needs 3 actions:
; 1. remove for memory
; 2. remove from db
; 3. add updated version to memory and db
(defn add-data-to-replacement2 [db-match partial-transaction]
  (-> partial-transaction
        (assoc :amount (-> partial-transaction :source :amount))
        (assoc :description (-> partial-transaction :source :description))
        (assoc :date (-> partial-transaction :source :date))
        (assoc :date-index (:date-index db-match))
        (assoc-attribute [:category-id (-> db-match :category-id)])))

(defn add-data-to-replacement3 [db-match bank-transaction]
  {:old {:user-id (:user-id bank-transaction) :date (:date db-match) :date-index (:date-index db-match)}
   :new (-> bank-transaction
      (assoc :user-id (:user-id bank-transaction))
      (assoc :amount (-> bank-transaction :source :amount))
      (assoc :description (-> bank-transaction :source :description))
      (assoc :date (-> bank-transaction :source :date))
      (assoc :date-index 0) ; needs to be calculated after join with other transactions
      (assoc-attribute [:marked-by-filter? (:marked-by-filter? db-match)])
      (assoc-attribute [:category-id (-> db-match :category-id)]))})

(defn add-data-to-new [[idx new-transaction]]
  (let [trans {:temp-id (str idx)
               :amount (:amount new-transaction)
               :date (:date new-transaction)
               :description (:description new-transaction)
               :source new-transaction
               :category-id nil}]
    (reduce assoc-attribute {} trans)))

;; create merge-service
(defn process-transactions-from-bank [transactions-in-db transactions-from-bank]
  ;; (println  transactions-in-db)
  (let [lookup-map (->> transactions-in-db
                        (map (juxt :amount #(select-keys % [:date :description :date-index :category-id :user-id])))
                        (reduce (fn [acc [k v]] (let [list (get acc k)
                                                      v2 (assoc v :index (count list))]
                                                  (assoc acc k (conj list v2)))) {}))
        ;; list of actions matches transactions-from-bank; leftover-map = unmatched db entries
        {actions :actions leftover-map :lookup-map} (map-to-actions lookup-map transactions-from-bank)
        ;unchanged
        replacements (->> (map vector actions transactions-from-bank)
                          (filter (fn [[{action :action} _]] (= action :replace)))
                          (map (fn [[{db-match :db-match} trans]] (add-data-to-replacement3 db-match trans))))
        new-trans (->> (map vector actions transactions-from-bank)
                       (filter (fn [[{action :action} _]] (= action :keep)))
                       (map (fn [[_ trans]] trans)))
        ;; orphans: db transactions in the pulled window that no bank transaction accounts for
        bank-dates (keep :date transactions-from-bank)
        lo (when (seq bank-dates) (apply min bank-dates))
        hi (when (seq bank-dates) (apply max bank-dates))
        orphans (when (seq bank-dates)
                  (->> (vals leftover-map)
                       (apply concat)
                       (filter #(<= lo (:date %) hi))
                       (map #(select-keys % [:user-id :date :date-index]))
                       vec))]
    [replacements new-trans (vec orphans)]))

(defn date->iso [iso-date]
  (-> iso-date
      date/iso-date-str->date
      date/date->unixtime))

(comment
  (process-transactions-from-bank
   [{:amount 2.0 :date (date->iso "2025-11-10") :date-index 1 :description "cat"}
    {:amount 3.0 :date (date->iso "2025-11-10") :date-index 2 :description "asdg" :category-id "1234"}
    {:amount 3.0 :date (date->iso "2025-11-10") :date-index 3 :description "asdf"}]
   [{:amount 2.0 :date (date->iso "2025-11-10") :description "cat" :source {:amount 2.0 :date (date->iso "2025-11-10") :description "cat"}}
    {:amount 3.0 :date (date->iso "2025-11-10") :description "asdf" :source {:amount 3.0 :date (date->iso "2025-11-10") :description "asdf"}}
    {:amount 3.0 :date (date->iso "2025-11-12") :description "asdf" :source {:amount 3.0 :date (date->iso "2025-11-12") :description "asdf"}}
    {:amount 5.0 :date (date->iso "2025-11-12") :description "asdf" :source {:amount 5.0 :date (date->iso "2025-11-12") :description "asdf"}}
    {:amount 6.0 :date (date->iso "2025-11-12") :description "asdf" :source {:amount 6.0 :date (date->iso "2025-11-12") :description "asdf"}}]))
