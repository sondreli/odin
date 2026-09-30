(ns client.services.chart-service
  (:require ["d3" :as d3]
            [clojure.string :as s]
            [client.services.date-service :as date]
            [re-frame.core :refer [dispatch]]
            [goog.object :as g]
            [goog.string :as gstring]
            
            [client.events.utils :as utils]))

(defn sum-category [[category-id transactions]]
  {:category-id category-id
   :amount (->> transactions
                (map :amount)
                (apply +))})

(defn sum-month [[mnt transactions]]
  (if (empty? transactions)
    [{:category-id nil :amount 0 :month mnt}]
    (->> transactions
         (group-by #(-> % :category-id))
         (seq)
         (map sum-category)
         (map #(assoc % :month mnt)))))

(defn make-index [data & key-funcs]
  (if (-> key-funcs count (= 0))
    ;; (first data)
    data
    (->> (group-by (first key-funcs) data)
         (mapcat (fn [[k v]] [k (apply (partial make-index v) (rest key-funcs))]))
         (apply hash-map))))

(def data [{:month "Feb" :category-name "ting" :amount -10}
           {:month "Feb" :category-name "mat" :amount -23}
           {:month "Feb" :category-name "bil" :amount -43}
           {:month "Mar" :category-name "ting" :amount -65}
           {:month "Mar" :category-name "mat" :amount -12}
           {:month "Mar" :category-name "bil" :amount -98}])

(defn next-serie [last-serie keys value index]
  (let [index-lookup (fn [key serie-value] (-> index (get value) (get key) :amount (+ serie-value)))]
    (map (fn [serie-value key] [serie-value (index-lookup key serie-value)]) last-serie keys)))

(defn add-serie [index keys series value]
  (let [last-serie (if (empty? series)
                     (repeat (count keys) 0)
                     (map last (last series)))
        next-serie (next-serie last-serie keys value index)]
    (conj series (into [] next-serie))))

(defn make-series [data key-fun value-fun]
  (let [keys (->> data (map key-fun) (into #{}) (into []))
        values (->> data (map value-fun) (into #{}) (into []))
        index (make-index data value-fun key-fun)
        fun (partial add-serie index keys)]
    (reduce fun [] values)))

;; (make-series ["ting" "mat" "bil"]
;;              ["Feb" "Mar"]
;;              (make-index data :month :category-name)
;;              )

;; (make-series data :category-name :month)

;; (make-index data :month :category-name)

;; (defn obj->clj
;;   [obj]
;;   (if (goog/isObject obj)
;;     (-> (fn [result key]
;;           (let [v (goog.object/get obj key)]
;;             (if (= "function" (goog/typeOf v))
;;               result
;;               (assoc result key (obj->clj v)))))
;;         (reduce {} (.getKeys goog/object obj)))
;;     obj))

(defn make-d3-series [data]
  (let [
        stack-generator (-> d3
                            .stack
                            (.keys (d3/union (clj->js (->> data
                                                           (sort-by #(-> % :amount -))
                                                           (map :category-id)))))
                            ;;  (.value (fn [[_ obj] key] (.log js/console (-> obj (.get key) clj->js (goog.object/get "amount")))))
                            (.value (fn [[_ obj] key] (-> obj (.get key) clj->js (goog.object/get "amount"))))
                            (.offset (.-stackOffsetDiverging d3))
                            ;;  clj-index (.value (fn [obj key] (-> obj second (goog.object/get key) (goog.object/get "amount"))))
                            )
        ;;  (.value (fn [[_ group] key] (.log js/console key) (-> group js->clj (get key) :amount))))
        ;; series2 (series (d3/index (clj->js data) #(:category-name %) #(:month %)))
        ;; series2 (series (clj->js (make-index datann :category-name :month)))
        d3-index (d3/index data
                           #(:month %)
                           #(:category-id %))
        clj-index (clj->js (into [] (make-index data :date :category-id)))
        series (stack-generator d3-index)
        ]
    ;; (.log js/console (clj->js (make-index datann :month :category-name)))
    ;; (.log js/console (d3/index (clj->js [{:category-name "a" :month 1}
    ;;                                      {:category-name2 "b" :month2 2}])
    ;;                            (fn [a] (clj->js (:category-name (js->clj a))))
    ;;                            (fn [a] (clj->js (:month (js->clj a))))))
    ;; (js->clj (d3/index (into-array data) (fn [a] (:category-name a))))
    ;; (clj->js (make-index data :month :category-name))
    (.log js/console series)
    series
    ))

(defn to-iso-date [date]
  (let [parts (s/split date #"-")
        label->num {"Jan" "01" "Feb" "02" "Mar" "03" "Apr" "04"
                    "Mai" "05" "Jun" "06" "Jul" "07" "Aug" "08"
                    "Sep" "09" "Okt" "10" "Nov" "11" "Des" "12"
                    "Q1" "01" "Q2" "04" "Q3" "07" "Q4" "10"}]
    (cond
      ;; 4-digit year (year bucket: "2026")
      (and (= 1 (count parts)) (= 4 (count (first parts))))
      (first parts)

      ;; yy-Mon or yy-Qn (month bucket or quarter bucket)
      (= 2 (count parts))
      (str (first parts) "-" (get label->num (second parts) "00"))

      ;; day-of-month ("01".."31") or anything else: pass through
      :else date)))

;; The scale and the SVG must share this width. A wider scale draws the last
;; bar past the viewBox, which clips it — most of the bar, once there are many.
(def ^:private barchart-width 900)
(def ^:private barchart-margin-left 40)
(def ^:private barchart-margin-right 16)

(defn x-scale [data]
  (let [groupSort (d3/groupSort data
                            (fn [D] (d3/sum (clj->js D) (fn [d] (goog.object/get d "amount"))))
                            (fn [d] (-> d clj->js (goog.object/get "month"))))
        domain (->> data
                        (map :month)
                        (into #{})
                        (into [])
                        (map #(vector (to-iso-date %) %))
                        (sort-by first)
                        (map second))
        scale (-> d3
                  .scaleBand
                  (.domain domain)
                  (.range [barchart-margin-left (- barchart-width barchart-margin-right)])
                  (.padding 0.1))]
    scale))

(defn y-scale [series height marginBottom]
  (let [max-val (d3/max series (fn [d] (d3/max d (fn [d] (get d 1)))))
        min-val (d3/min series (fn [d] (d3/min d (fn [d] (get d 0)))))
        max (if (and (some? max-val) (not (js/isNaN max-val))) max-val 0)
        min (if (and (some? min-val) (not (js/isNaN min-val))) min-val 0)
        ;; avoid degenerate domain when min=max (e.g. empty data)
        [domain-min domain-max] (if (= min max) [(- min 1) (+ max 1)] [min max])
        marginTop 24]
    (-> d3
        .scaleLinear
        (.domain (clj->js [domain-min domain-max]))
        (.rangeRound (clj->js [(- height marginBottom) marginTop])))))

(defn make-colors [series color-map]
  (let [range (.map series (fn [d] (get color-map (g/get d "key"))))
        ]
    (-> d3
      .scaleOrdinal
      (.domain (.map series (fn [d] (g/get d "key"))))
      ;; (.range (get d3/schemeSpectral (count series)))
      (.range range)
      (.unknown "#ccc"))))

(defn show-tooltip-label [select opacity]
  (-> d3
      (.select select)
      (.transition)
      (.duration "50")
      (.style "opacity" opacity)))

(defn position-tooltip-label [select event tooltip-text]
  (-> d3
      (.select select)
      (.html tooltip-text)
      (.style "left" (-> event (.-pageX) (+ 10) (str "px")))
      (.style "top" (-> event (.-pageY) (- 15) (str "px")))))

(defn make-chart [data series color category-map period]
  (let [x (x-scale data)
        narrow? (< (.bandwidth x) 40)
        marginBottom (if narrow? 60 20)
        height (if narrow? 540 500)
        y (y-scale series height marginBottom)
        marginLeft barchart-margin-left
        month-index (make-index data :month)
        div (-> d3
                (.select "body")
                (.append "div")
                (.attr "class" "tooltip-barchart")
                (.style "opacity" "0"))
        svg (-> d3
                (.select "#mychart")
                (.append "svg")
                (.attr "width" barchart-width)
                (.attr "height" height)
                (.attr "viewBox" (clj->js [0 0 barchart-width height]))
                (.attr "style" "max-width: 100%; height: auto;")
                (.append "g")
                (.selectAll)
                (.data series)
                (.join "g")
                (.attr "fill" (fn [d] (color (g/get d "key"))))
                (.selectAll "rect")
                (.data (fn [D] (.map D (fn [d] (g/set d "key" (g/get D "key")) d))))
                (.join "rect")
                (.attr "x" (fn [d] (x (first (g/get d "data")))))
                ;; (.attr "x" (fn [d] (x (g/get (g/get d "data") "group"))))
                (.attr "y" (fn [d]
                  (let [v0 (g/get d 0)
                        v1 (g/get d 1)
                        top (if (and (number? v0) (number? v1) (not (js/isNaN v0)) (not (js/isNaN v1)))
                              (max v0 v1)
                              js/NaN)]
                    (if (js/isNaN top)
                      (- height marginBottom)
                      (y top)))))
                (.attr "width" (.bandwidth x))
                (.attr "height" (fn [d]
                  (try
                    (let [v0 (g/get d 0)
                          v1 (g/get d 1)
                          y0 (y v0)
                          y1 (y v1)
                          raw (abs (- y0 y1))]
                      (if (or (js/isNaN raw) (neg? raw)) 0 raw))
                    (catch :default _ 0))))
                (#(doto %
                    (.on "mouseover" (fn [event d]
                                       (let [key (g/get d "key")
                                             category-name (->> key (get category-map) :name)
                                             category-amount (-> d (g/get "data") (get 1) (.get key) :amount)
                                             tooltip-text (str category-name " - " category-amount)]
                                         (-> d3 (.selectAll "#mychart rect")
                                             (.transition)
                                             (.duration 150)
                                             (.attr "opacity" "0.4"))
                                         (this-as this (-> d3 (.select this)
                                                           (.transition)
                                                           (.duration 150)
                                                           (.attr "opacity" "1")))
                                         (show-tooltip-label "div.tooltip-barchart" "1")
                                         (position-tooltip-label "div.tooltip-barchart" event tooltip-text))))
                    (.on "mouseout" (fn [d i]
                                      (-> d3 (.selectAll "#mychart rect")
                                          (.transition)
                                          (.duration 150)
                                          (.attr "opacity" "1"))
                                      (show-tooltip-label "div.tooltip-barchart" "0")))
                    (.on "click" (fn [event d]
                                   (let [category (->> (g/get d "key") (get category-map) :name)
                                         month (-> d (g/get "data") first)
                                         sub-period (date/date-label->period month period)]
                                     (show-tooltip-label "div.tooltip-barchart" "0")
                                     (dispatch [:navigate [sub-period :table [category]]]))))))
                )
        ; horizontal axis
        tilt? (< (.bandwidth x) 40)
        svg2 (-> d3
                 (.select "#mychart svg")
                 (.append "g")
                 (.attr "transform" (str "translate(0," (- height marginBottom) ")"))
                 (.attr "fill" "currentColor")
                 (.call (-> d3 (.axisBottom x) (.tickSizeOuter 0)))
                 (.call (fn [g]
                          (-> g (.selectAll ".domain") (.remove))
                          (when tilt?
                            (-> g (.selectAll ".tick text")
                                (.style "text-anchor" "end")
                                (.attr "transform" "rotate(-45,0,9)")))))
                 (.selectAll ".tick")
                 (.data (.domain x))
                 (.on "mouseover" (fn [event d]
                                    (let [sum-neg-amount (->> (get month-index d)
                                                              (filter #(-> % :amount pos?))
                                                              (map :amount)
                                                              (apply +)
                                                              (* -1))
                                          sum-pos-amount (->> (get month-index d)
                                                              (filter #(-> % :amount neg?))
                                                              (map :amount)
                                                              (apply +)
                                                              (* -1))
                                          tooltip-text (str d "<br/>out: " sum-neg-amount "<br/>In: " sum-pos-amount)]
                                      (show-tooltip-label "div.tooltip-barchart" "1")
                                      (position-tooltip-label "div.tooltip-barchart" event tooltip-text))))
                 (.on "mouseout" (fn [d i] (show-tooltip-label "div.tooltip-barchart" "0")))
                 (.on "click" (fn [event d] (let [sub-period (date/date-label->period d period)]
                                              (show-tooltip-label "div.tooltip-barchart" "0")
                                              (dispatch [:navigate [sub-period :table []]])))))
                 
        ; vertical axis
        svg3 (-> d3
                 (.select "#mychart svg")
                 (.append "g")
                 (.attr "transform" (str "translate(" marginLeft ",0)"))
                 (.call (-> d3 (.axisLeft y) (.ticks nil "s")))
                 (.call (fn [g] (-> g (.selectAll ".domain") (.remove)))))

        ; bar total labels
        bar-totals (->> (.domain x)
                        (map (fn [month]
                               (let [items (get month-index month)
                                     pos-sum (->> items (filter #(pos? (:amount %))) (map :amount) (reduce + 0))
                                     neg-sum (->> items (filter #(neg? (:amount %))) (map :amount) (reduce + 0))]
                                 {:month month :pos-sum pos-sum :neg-sum neg-sum}))))
        _ (-> d3
              (.select "#mychart svg")
              (.append "g")
              (.selectAll "text.bar-total")
              (.data (clj->js bar-totals))
              (.join "text")
              (.attr "class" "bar-total")
              (.attr "x" (fn [d] (+ (.call x nil (g/get d "month")) (/ (.bandwidth x) 2))))
              (.attr "y" (fn [d]
                           (let [pos (g/get d "pos-sum")]
                             (if (pos? pos) (- (y pos) 4) (- (y 0) 4)))))
              (.attr "text-anchor" (if tilt? "start" "middle"))
              (.attr "font-size" "11px")
              (.attr "fill" "#555")
              (#(if tilt?
                  (-> % (.attr "transform" (fn [d]
                          (let [cx (+ (.call x nil (g/get d "month")) (/ (.bandwidth x) 2))
                                pos (g/get d "pos-sum")
                                cy (if (pos? pos) (- (y pos) 4) (- (y 0) 4))]
                            (str "rotate(-45," cx "," cy ")")))))
                  %))
              (.text (fn [d]
                       (let [pos (g/get d "pos-sum")]
                         (when (pos? pos)
                           (gstring/format "%.0f" pos))))))]
    svg3))

(defn period-length [period]
  (let [days (-> (- (:end period) (:start period))
                 (/ 1000)
                 (/ 60)
                 (/ 60)
                 (/ 24))]
    (cond
      (< days 32) :month
      (< days 370) :year
      (< days 732) :year
      :else :year)))

(defn add-uncategorized-ids [transaction]
  (cond
      (and (-> transaction :category-id nil?)
           (-> transaction :amount pos?)) (assoc transaction :category-id "ukategorisert-in")
      (and (-> transaction :category-id nil?)
           (-> transaction :amount neg?)) (assoc transaction :category-id "ukategorisert-out")
      :else transaction))

(defn- all-day-labels
  "Generate all day labels (01..28/29/30/31) for a month period."
  [period]
  (let [start (:start period)
        end (:end period)
        start-day (.getDate start)
        ;; end is exclusive (first of next month), so last day = end - 1 day
        last-day (.getDate (js/Date. (- (.getTime end) 1)))]
    (mapv #(if (< % 10) (str "0" %) (str %))
          (range start-day (inc last-day)))))

(defn- all-month-labels
  "Generate all month labels (yy-Mon) for a year/multi-year period."
  [period]
  (let [start (:start period)
        end (:end period)]
    (loop [d (js/Date. (.getTime start))
           labels []]
      (if (>= (.getTime d) (.getTime end))
        labels
        (let [label (date/get-month-label (.getTime d))]
          (recur (js/Date. (.getFullYear d) (inc (.getMonth d)) 1)
                 (conj labels label)))))))

(defn- all-quarter-labels
  "Generate quarter labels (yy-Qn) for every quarter touched by the period."
  [period]
  (let [start (:start period)
        end (:end period)
        ;; align to the start of the start month's quarter
        q-month (* 3 (quot (.getMonth start) 3))]
    (loop [d (js/Date. (.getFullYear start) q-month 1)
           labels []]
      (if (>= (.getTime d) (.getTime end))
        labels
        (recur (js/Date. (.getFullYear d) (+ 3 (.getMonth d)) 1)
               (conj labels (date/get-quarter-label (.getTime d))))))))

(defn- all-year-labels
  "Generate year labels for every year touched by the period."
  [period]
  (let [start (:start period)
        end (:end period)]
    (loop [d (js/Date. (.getFullYear start) 0 1)
           labels []]
      (if (>= (.getTime d) (.getTime end))
        labels
        (recur (js/Date. (inc (.getFullYear d)) 0 1)
               (conj labels (date/get-year-label (.getTime d))))))))

(defn period-transactions->data [period-transactions period]
  (let [pt (:period-type period)
        _ (println "period-transactions->data period-type:" pt
                   "start:" (.toISOString (:start period))
                   "end:" (.toISOString (:end period)))
        [label-fx all-labels]
        (case pt
          :month    [date/get-date-label    (all-day-labels period)]
          :months   [date/get-month-label   (all-month-labels period)]
          :quarter  [date/get-month-label   (all-month-labels period)]
          :quarters [date/get-quarter-label (all-quarter-labels period)]
          :year     [date/get-month-label   (all-month-labels period)]
          :years    [date/get-year-label    (all-year-labels period)]
          ;; legacy fallback by span-length when period-type is missing/unknown
          (case (period-length period)
            :month [date/get-date-label  (all-day-labels period)]
            [date/get-month-label (all-month-labels period)]))
        grouped (->> period-transactions
                     (map add-uncategorized-ids)
                     (group-by #(-> % :date label-fx)))
        complete-grouped (reduce (fn [m label]
                                   (if (contains? m label)
                                     m
                                     (assoc m label [])))
                                 grouped
                                 all-labels)]
    (->> complete-grouped
         (seq)
         (mapcat sum-month)
         (map #(update % :amount (fn [a] (- a)))))))

(defn draw-stacked-barchart [transactions categories period chart-size]
  (println chart-size)
  (let [category-map (into {} (map (juxt :id #(identity %))
                                   (-> categories
                                       (conj {:id "ukategorisert-in" :name "ukategorisert-in"})
                                       (conj {:id "ukategorisert-out" :name "ukategorisert-out"})))) 
        chart-transactions (if (= chart-size :full)
                             transactions
                             (filter #(-> % :amount neg?) transactions))
        data (period-transactions->data chart-transactions period)
        ;; _ (println "draw-stacked-barchart: " data)
        _ (.log js/console data)
        color-map (-> (into {} (map (fn [c] [(:id c) (or (:color c) "#ccc")]) categories))
                      (assoc "ukategorisert-in" "#ddd")
                      (assoc "ukategorisert-out" "#edd"))
        ;; _ (println "draw-stacked-barchart: color-map " color-map)
        series (make-d3-series data)
        ;; x (x-scale data)
        ;; y (y-scale series 1000)
        color (make-colors series color-map)
        chart (make-chart data series color category-map period)]
    ;;  (println color-map)
    ;;  (.log js/console x)
    ))