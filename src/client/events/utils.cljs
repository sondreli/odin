(ns client.events.utils
  (:require [client.services.date-service :as date]
            [clojure.set :as set]
            [clojure.string :as s]
            [common.category-service :as category]
            [cljs.spec.alpha :as spec]
            [re-frame.core :refer [after]]))

(def ^:private unicode-replacements
  [["\u2018" "'"]   ; left single quote
   ["\u2019" "'"]   ; right single quote
   ["\u201C" "\""]  ; left double quote
   ["\u201D" "\""]  ; right double quote
   ["\u2013" "-"]   ; en-dash
   ["\u2014" "-"]   ; em-dash
   ["\u2026" "..."] ; ellipsis
   ["\u00A0" " "]   ; non-breaking space
   ["\u2010" "-"]   ; hyphen
   ["\u2011" "-"]   ; non-breaking hyphen
   ["\u2012" "-"]   ; figure dash
   ["\u2032" "'"]   ; prime
   ["\u2033" "\""]  ; double prime
   ["\u00AB" "\""]  ; left guillemet
   ["\u00BB" "\""]  ; right guillemet
   ["\u02BC" "'"]   ; modifier letter apostrophe
   ["\u2024" "."]   ; one dot leader
   ["\uFF1A" ":"]   ; fullwidth colon
   ["\uFF0E" "."]   ; fullwidth full stop
   ["\uFF08" "("]   ; fullwidth left paren
   ["\uFF09" ")"]   ; fullwidth right paren
   ["\uFF3B" "["]   ; fullwidth left bracket
   ["\uFF3D" "]"]   ; fullwidth right bracket
   ["\uFF5B" "{"]   ; fullwidth left brace
   ["\uFF5D" "}"]   ; fullwidth right brace
   ["\uFF0A" "*"]   ; fullwidth asterisk
   ["\uFF0B" "+"]   ; fullwidth plus
   ["\uFF1F" "?"]   ; fullwidth question mark
   ["\uFF5C" "|"]   ; fullwidth vertical line
   ["\uFF3C" "\\"]  ; fullwidth backslash
   ["\uFF04" "$"]   ; fullwidth dollar
   ["\uFF3E" "^"]   ; fullwidth caret
   ["\u02C6" "^"]]) ; modifier letter circumflex

(defn sanitize-input [text]
  (reduce (fn [s [from to]] (s/replace s from to))
          (or text "")
          unicode-replacements))

(defn check-and-throw
  "Throws an exception if `db` doesn't match the Spec `a-spec`."
  [a-spec db]
  (when-not (spec/valid? a-spec db)
    (throw (ex-info (str "spec check failed: " (spec/explain-str a-spec db)) {}))))

;; now we create an interceptor using `after`
(def check-spec-interceptor (after (partial check-and-throw :client.db/db)))

(defn filter-ukategorsert [fx transactions]
  (filter #(and (-> % :category-id nil?)
                (-> % :amount fx)) transactions))

(defn filter-category [transactions category category-map]
  (cond
    (= category "ukategorisert-in") (filter-ukategorsert pos? transactions)
    (= category "ukategorisert-out") (filter-ukategorsert neg? transactions)
    :else (filter #(->> % :category-id (get category-map) :name (= category)) transactions)))

(defn apply-filter-path [transactions filter-path category-map & [selected-tag-id]]
  (let [filtered (case (count filter-path)
                   0 transactions
                   1 (into [] (filter-category transactions (first filter-path) category-map))
                   2 (let [match-category {:marker {:description [(second filter-path)]}}]
                       (->> transactions
                            (filter #(category/match? match-category %))
                            (into []))))]
    (if selected-tag-id
      (filterv #(some #{selected-tag-id} (:tag-ids %)) filtered)
      filtered)))

(defn assoc-amount [category-map category-id transaction-amount]
  (let [acc-amount (-> category-map (get category-id) :amount)]
    (assoc-in category-map [category-id :amount] (+ acc-amount transaction-amount))))

(defn add-amount-to-amount-map [amount-map transaction]
  (cond
      (and (-> transaction :category-id nil?)
           (-> transaction :amount pos?)) (assoc-amount amount-map "ukategorisert-in" (:amount transaction))
      (and (-> transaction :category-id nil?)
           (-> transaction :amount neg?)) (assoc-amount amount-map "ukategorisert-out" (:amount transaction))
      :else (assoc-amount amount-map (:category-id transaction) (:amount transaction))))

(defn sum-categoires [categories transactions]
  (let [extended-categories (conj categories
                                  {:id "ukategorisert-in" :name "ukategorisert-in"}
                                  {:id "ukategorisert-out" :name "ukategorisert-out"})
        amount-map (into {} (map #(vector (:id %) {:amount 0}) extended-categories))
        summed-amount-map (reduce add-amount-to-amount-map amount-map transactions)
  ;; _ (println "sum-categories: " extended-categories)
  ;; _ (println summed-amount-map)
        summed-categories (->> extended-categories ;(conj categories {:id "ukategorisert" :name "ukategorisert"})
                               (map #(assoc % :amount (-> summed-amount-map (get (:id %)) :amount)))
                               (sort-by :amount))
        total-out (->> summed-categories (map :amount) (filter neg?) (apply +))
        total-in (->> summed-categories (map :amount) (filter pos?) (apply +))
        accounting (concat summed-categories [{:id "out" :name "out" :amount total-out}
                                              {:id "in" :name "in" :amount total-in}])]
    accounting))

(defn sum-categoires2 [categories transactions])

(defn displayed-transactions-data [db
                              new-period-transactions
                              new-filter-path
                              new-builder-category]
  (let [period-transactions (if (some? new-period-transactions)
                              new-period-transactions
                              (-> db :period-transactions))
        filter-path (if (some? new-filter-path)
                      new-filter-path
                      (-> db :filter-path))
        builder-category (if (some? new-builder-category)
                           new-builder-category
                           (-> db :builder-category))
        category-map (into {} (map (juxt :id #(identity %)) (:categories db)))]
    
    {:displayed-transactions (-> period-transactions
                                  (apply-filter-path filter-path category-map (:selected-tag-id db))
                                  ;; (category/mark-transactions builder-category)
                                  (category/add-category2 builder-category)
                                  :updated-seq
                                  )
     :display-option :table}))

(defn sort-transactions [transactions displayed-transactions-data]
  (let [sort-column (:sort-column displayed-transactions-data)
        sort-order (:sort-order displayed-transactions-data)]
    (cond->> transactions
      (some? sort-column) (sort-by sort-column)
      (and (some? sort-column) (= sort-order :reverse)) reverse
      :else (into []))))

(defn apply-category [db new-filter-path]
  (let [filter-path (if (some? new-filter-path) new-filter-path (:filter-path db))
        category-map (into {} (map (juxt :id #(identity %)) (:categories db)))
        displayed-transactions (-> (:period-transactions db)
                                    (apply-filter-path filter-path category-map (:selected-tag-id db))
                                    ;; (category/mark-transactions (db :builder-category))
                                    (category/add-category2 (:builder-category db))
                                    :updated-seq
                                    (sort-transactions (:displayed-transactions-data db))
                                    )
  ]
    (-> db
        (assoc :filter-path filter-path)
        (assoc-in [:displayed-transactions-data :displayed-transactions] displayed-transactions))))

;; (defn period [period-selector]
;;   (println "period")
;;   (println period-selector)
;;   (if (-> period-selector :length (= :a-month))
;;     (date/month-period (-> period-selector :a-month :month-index)
;;                  (-> period-selector :a-month :year))
;;     (date/last-year-period)))

(defn apply-period [db period-selector period]
  (let [period-transactions (->> (date/period-transactions (:all-transactions db) period)
                                 reverse
                                 (into []))
        categories (:categories db)
        summed-categories (sum-categoires categories period-transactions)
        displayed-transactions-data (displayed-transactions-data db period-transactions nil nil)]
    (-> db
         (assoc :period period)
         (assoc :period-selector period-selector)
         (assoc :period-transactions period-transactions)
        ;;  (assoc :displayed-transactions displayed-transactions)
         (assoc :displayed-transactions-data displayed-transactions-data)
         (assoc :summed-categories summed-categories))))

(defn period-transactions [all-transactions period]
  (->> (date/period-transactions all-transactions period)
       reverse
       (into [])))

(defn summed-categories [db period-transactions]
  (sum-categoires (:categories db) period-transactions))

(defn period-type->time-unit [period]
  (let [period-type (:period-type period)]
    (case period-type
      :year     :year
      :years    :year
      :months   :month
      :month    :month
      :quarter  :month
      :quarters :month
      nil)))

(defn apply-period2 [db input-all-transactions input-period]
  (if (or (some? input-period)
          (some? input-all-transactions))
    (let [all-transactions (if (some? input-all-transactions) input-all-transactions (:all-transactions db))
          period (if (some? input-period) input-period (:period db))
          ;; time-unit (date/time-unit-from-period period)
          time-unit (period-type->time-unit period)
          long-view (date/long-view-from-period db period time-unit)
          period-transactions (period-transactions all-transactions period)
          displayed-transactions (if (db :builder-category)
                                      (-> period-transactions
                                          (category/add-category2 (db :builder-category))
                                          :updated-seq
                                          (sort-transactions (:displayed-transactions-data db)))
                                      (sort-transactions period-transactions (:displayed-transactions-data db)))]
      (-> db
          (assoc :period period)
          (assoc-in [:period-selector :selected-period] period)
          (assoc-in [:period-selector :period-type] (-> period :period-type))
          (assoc-in [:period-selector :long-view] long-view)
          (assoc-in [:period-selector :time-unit] time-unit)
          (assoc :period-transactions period-transactions)
          (assoc :summed-categories (summed-categories db period-transactions))
          (assoc-in [:displayed-transactions-data :displayed-transactions] displayed-transactions)
          ))
    db))

(defn get-display-option [db display-option]
  (if (nil? display-option)
    (-> db :displayed-transactions-data :display-option)
    display-option))

(defn apply-display-option [db maybe-display-option]
  (let [display-option (if (nil? maybe-display-option)
                         (-> db :displayed-transactions-data :display-option)
                         maybe-display-option)] 
    (assoc-in db [:displayed-transactions-data :display-option] display-option)))

; period triggers: period, period-selector, period-transactions, displayed-transactions, summed-categories
; category triggers: filter-path, displayed-transactions
; should be rewritten to output a data struct for each widget
; then the widget would only subscripte to that data struct
(defn apply-update [db all-transactions period filter-path display-option]
  (-> db
      (apply-display-option display-option)
      (apply-period2 all-transactions period)
      (apply-category filter-path)))