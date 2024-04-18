(ns client.services.date-service
  (:require [clojure.string :as s]
             ))

(defn datetime2date [datetime]
  (-> datetime (s/split #"T") first))

(defn first-day-of-this-month []
  (let [today (js/Date.)
        month (-> today .getMonth (+ 1))
        year (.getFullYear today)]
    (str year "-" month "-01")))

(defn last-day-of-this-month []
  (let [today (js/Date.)
        month (.getMonth today)
        year (.getFullYear today)
        last-day (-> (js/Date. year (+ month 1) 1) .toISOString datetime2date)]
    last-day))

(defn current-month []
  (let [today (js/Date.)]
    (-> today .getMonth str)))

(defn current-year []
  (let [today (js/Date.)]
    (-> today .getFullYear str)))

(defn first-day-of-month [month-index year]
  (let [month (-> month-index js/parseInt)
        first-day (js/Date. year month 1)]
    first-day))

(defn last-day-of-month [month-index]
  (let [today (js/Date.)
        month (-> month-index js/parseInt (+ 1))
        year (.getFullYear today)
        last-day (-> (js/Date. year month 1) .toISOString datetime2date)]
    last-day))

(defn first-day-of-next-month [month-index year]
  (let [month (-> month-index js/parseInt (+ 1))
        last-day (js/Date. year month 1)]
    last-day))

(defn one-year-ago []
  (let [today (js/Date.)
        date (.getDate today)
        month (.getMonth today)
        last-year (-> today .getFullYear dec)]
    (js/Date. last-year month date)))

(defn tomorrow []
  (let [today (js/Date.)
        tomorrow (.setDate today (-> today .getDate inc))]
    (js/Date. tomorrow)))

(defn transaction-in-period? [transaction start end]
;;   (let [trans-date (-> transaction :classificationInput :date datetime2date js/Date.)
  (let [trans-date (-> transaction :date js/Date.)
        start-date (js/Date. start)
        end-date (js/Date. end)
        ]
    (and (>= trans-date start-date)
         (< trans-date end-date))))

(defn period-transactions [all-transactions period]
  (filter #(transaction-in-period? % (:start period) (:end period)) all-transactions))

(defn month-period [month-index year]
  {:start (first-day-of-month month-index year)
   :end (first-day-of-next-month month-index year)})

(defn last-year-period []
  {:start (one-year-ago)
   :end (tomorrow)})

(defn unixtime->prettydate [unixtime]
  (let [date (js/Date. unixtime)
        string-date (.toLocaleDateString date)
        [month day year] (s/split string-date #"/")
        ]
    (str day "." month "." year)))

(defn unixtime->localdate [unixtime]
  (js/Date. (js/parseInt unixtime)))

(defn localdate->unixtime [localdate]
  (let [unixtime (-> localdate .getTime Math/floor)]
    unixtime))

(defn transaction-years [transactions]
  (let [first-year (-> transactions first :date js/Date. .getFullYear)
        last-year (-> transactions last :date js/Date. .getFullYear)
        unique-years (->> (range (int first-year) (-> last-year int inc))
                          reverse
                          (into []))]
    unique-years))

(defn get-date-label [unixtime]
  (let [date (-> unixtime js/Date. .getDate)]
    (if (-> date (< 10))
      (str "0" date)
      date)))

(defn get-month-label [unixtime]
  (let [date (js/Date. unixtime)
        month (.getMonth date)
        year (apply str (take-last 2 (-> date .getFullYear str)))
        label (case month
                0 "Jan"
                1 "Feb"
                2 "Mar"
                3 "Apr"
                4 "Mai"
                5 "Jun"
                6 "Jul"
                7 "Aug"
                8 "Sep"
                9 "Okt"
                10 "Nov"
                11 "Des")
        ]
    (str year "-" label)))

(defn month-label->period [month-label]
  (let [[year-last2 label] (s/split month-label #"-")
        year (str "20" year-last2)
        month-index (case label
                      "Jan" 0
                      "Feb" 1
                      "Mar" 2
                      "Apr" 3
                      "Mai" 4
                      "Jun" 5
                      "Jul" 6
                      "Aug" 7
                      "Sep" 8
                      "Okt" 9
                      "Nov" 10
                      "Des" 11)]
    (month-period month-index year)))

(defn period [month-index year]
  {:start (first-day-of-month month-index year)
   :end (first-day-of-next-month month-index year)})

;; have to have short-view length/time-unit, unit position, relative/absolute view and long-view length 
;; relative can be when some units in the long-view have not happened
;; absolute can be when all units are known
;; long-view can be simple in to first round and only be the parent time-unit
;; then the function only need time-unit and unit position
(defn have-next-parent-unit? [start end]
  (let [a (last (butlast start))
        b (last (butlast end))]
    (> b a)))

(defn inc-parent-unit [view-start]
  (let [index (-> view-start count (- 2))
        index-last (-> view-start count dec)
        a (get view-start index)]
    (-> view-start
        (assoc index (inc a))
        (assoc index-last 0))))

(defn long-view [long-view-start long-view-end] ;[time-unit time-index]
  (let [time-unit (case (count long-view-start)
                    1 :year
                    2 :month
                    3 :day)
        number-of-units (case time-unit
                          :year 10
                          :month 12
                          :day 31)
        have-next-parent-unit (have-next-parent-unit? long-view-start long-view-end)
        start (last long-view-start)
        end (if have-next-parent-unit (dec number-of-units) (last long-view-end))
        indices (->> (range start (inc end))
                     (map #(concat(butlast long-view-start) [%])))
        ;; have to adjust month-indices and connect them with correct year
        ;;periods (map (fn [month-index] (period month-index year)) month-indices)
        ]
    (if have-next-parent-unit
      (concat indices (long-view (inc-parent-unit long-view-start) long-view-end))
      indices)
    ))



;; time-unit is specified by period-array length
;; if long-view contains time elements that has not happened, make it relative
;; only specify long-view, then short-view will be derived
(long-view [2024 0] [2024 12])

(defn init-long-view []
  (let [end-year (current-year)
        month (current-month)
        start-year (- end-year 1)]
    (long-view [start-year month] [end-year month])))