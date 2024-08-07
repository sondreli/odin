(ns client.events.utils
  (:require [client.services.date-service :as date]
            [clojure.set :as set]
            [common.category-service :as category]
            [cljs.spec.alpha :as spec]
            [re-frame.core :refer [after]]
   ))

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

(defn apply-filter-path [filter-path category-map transactions]
  (case (count filter-path)
    0 transactions
    1 (into [] (filter-category transactions (first filter-path) category-map))
    2 (let [match-category {:marker {:description [(second filter-path)]}}]
        (->> transactions
             (filter #(category/match? match-category %))
             (into [])))))

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
        _ (println "dtd period-transactions: " (count period-transactions))
        filter-path (if (some? new-filter-path)
                      new-filter-path
                      (-> db :filter-path))
        builder-category (if (some? new-builder-category)
                           new-builder-category
                           (-> db :builder-category))
        category-map (into {} (map (juxt :id #(identity %)) (:categories db)))]
    
    {:displayed-transactions (->> period-transactions
                                  (apply-filter-path filter-path category-map)
                                  (category/mark-transactions builder-category))
     :display-option :table}))

(defn apply-category [db new-filter-path]
  (let [filter-path (if (some? new-filter-path) new-filter-path (:filter-path db))
        category-map (into {} (map (juxt :id #(identity %)) (:categories db)))
        displayed-transactions (->> (db :period-transactions)
                                    (apply-filter-path filter-path category-map)
                                    (category/mark-transactions (db :builder-category)))]
    (println "apply-category: " filter-path)
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
    (println "apply-period")
  (let [period-transactions (->> (date/period-transactions (:all-transactions db) period)
                                 reverse
                                 (into []))
        categories (:categories db)
        summed-categories (sum-categoires categories period-transactions)
        _ (println "apply-period: summed-categories: " summed-categories)
        displayed-transactions-data (displayed-transactions-data db period-transactions nil nil)
        ]
    (-> db
         (assoc :period period)
         (assoc :period-selector period-selector)
         (assoc :period-transactions period-transactions)
        ;;  (assoc :displayed-transactions displayed-transactions)
         (assoc :displayed-transactions-data displayed-transactions-data)
         (assoc :summed-categories summed-categories))))

(defn period-transactions [db period]
  (->> (date/period-transactions (:all-transactions db) period)
       reverse
       (into [])))

(defn summed-categories [db period-transactions]
  (sum-categoires (:categories db) period-transactions))

(defn apply-period2 [db period]
  (if (some? period)
    (let [time-unit (date/time-unit-from-period period)
          long-view (date/long-view-from-period db period time-unit)
          period-transactions (period-transactions db period)
          displayed-transactions (->> period-transactions
                                      (category/mark-transactions (db :builder-category)))]
      (-> db
          (assoc :period period)
          (assoc-in [:period-selector :selected-period] period)
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
(defn apply-update [db period filter-path display-option]
  (println "apply-update/display-option: " display-option)
  (println "apply-update/filter-path: " filter-path)
  (-> db
      (apply-display-option display-option)
      (apply-period2 period)
      (apply-category filter-path)))