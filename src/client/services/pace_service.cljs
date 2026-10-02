(ns client.services.pace-service
  "Place today's expected spend on a budget tile or the summary bar.

   The server stores one curve per category for the current month. This
   namespace only reads that curve: it picks the value for today's date and
   turns it into a fraction of the tile."
  (:require [goog.string :as gstring]
            [goog.string.format]))

(defn current-month-key
  ([] (current-month-key (js/Date.)))
  ([date]
   (gstring/format "%04d-%02d" (.getFullYear date) (inc (.getMonth date)))))

(defn days-in-month
  [date]
  (.getDate (js/Date. (.getFullYear date) (inc (.getMonth date)) 0)))

(defn for-today
  "The stored curve belongs to one calendar month. Ignore it in any other month."
  [prediction]
  (when (and (map? prediction)
             (= (:month prediction) (current-month-key)))
    prediction))

(defn category-pace
  [prediction category-id]
  (when (and prediction category-id)
    (let [id (str category-id)]
      (some #(when (= id (str (:category-id %))) %)
            (:categories prediction)))))

(defn day-index
  "Index into a per-day vector. Day 1 is index 0. The last stored day is used
   if the vector is shorter than the calendar month."
  [date days]
  (let [n (max 1 (or days (days-in-month date)))]
    (-> (.getDate date)
        dec
        (max 0)
        (min (dec n)))))

(defn value-at
  [curve date days]
  (when (seq curve)
    (nth curve (day-index date days) nil)))

(defn expected-at
  [pace-cat date days]
  (value-at (:daily pace-cat) date days))

(defn marker-fraction
  "Where `expected` kroner sits on a bar of `basis` kroner. Stays inside the bar."
  [expected basis]
  (when (and (number? expected) (number? basis) (pos? basis))
    (-> (/ expected basis) (max 0) (min 1))))

(defn tile-marker
  "Tick fraction for one category, plus a band when the curve has one."
  [pace-cat date basis days]
  (when-let [expected (expected-at pace-cat date days)]
    (when-let [fraction (marker-fraction expected basis)]
      (let [low (value-at (:band-low pace-cat) date days)
            high (value-at (:band-high pace-cat) date days)]
        (cond-> {:fraction fraction :expected expected}
          (and (number? low) (number? high) (> high low))
          (assoc :band-low (marker-fraction low basis)
                 :band-high (marker-fraction high basis)))))))

(defn aggregate-expected
  "Sum of today's expected spend for the given category ids."
  [prediction category-ids date]
  (let [days (:days-in-month prediction)]
    (reduce (fn [sum id]
              (if-let [cat (category-pace prediction id)]
                (+ sum (or (expected-at cat date days) 0))
                sum))
            0
            category-ids)))

(defn aggregate-band
  "Combined low and high for today. Categories without a band contribute their
   expected amount to both ends, so the zone stays in the same units as the tick."
  [prediction category-ids date]
  (let [days (:days-in-month prediction)
        parts (keep (fn [id]
                      (when-let [cat (category-pace prediction id)]
                        (let [expected (or (expected-at cat date days) 0)]
                          {:low (or (value-at (:band-low cat) date days) expected)
                           :high (or (value-at (:band-high cat) date days) expected)
                           :banded? (boolean (or (seq (:band-low cat))
                                                 (seq (:band-high cat))))})))
                    category-ids)]
    (when (some :banded? parts)
      (let [low (reduce + 0 (map :low parts))
            high (reduce + 0 (map :high parts))]
        (when (> (- high low) 0.5)
          {:low low :high high})))))
