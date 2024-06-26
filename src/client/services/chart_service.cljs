(ns client.services.chart-service
  (:require ["d3" :as d3]
            [clojure.string :as s]
            [client.services.date-service :as date]
            [re-frame.core :refer [dispatch]]
            [goog.object :as g]
            [goog.string :as gstring]
            ))

(defn sum-category [[category-id transactions]]
  {:category-id category-id
   :amount (->> transactions
                (map :amount)
                (apply +))})

(defn sum-month [[mnt transactions]]
  (->> transactions
       (group-by #(-> % :category-id))
       (seq)
       (map sum-category)
       (map #(assoc % :month mnt))))

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
    series
    ))

(defn to-iso-date [date]
  (let [[year month] (s/split date #"-")
        label->num {"Jan" "01" "Feb" "02" "Mar" "03" "Apr" "04" "Mai" "05" "Jun" "06" "Jul" "07" "Aug" "08" "Sep" "09" "Okt" "10" "Nov" "11" "Des" "12"}]
    (str year "-" (get label->num month))))

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
        margin-left 40
        width 928
        margin-right 10
        scale (-> d3
                  .scaleBand
                  (.domain domain)
                  (.range [margin-left (- width margin-right)])
                  (.padding 0.1))]
    scale))

(defn y-scale [series]
  (let [max (d3/max series (fn [d] (d3/max d (fn [d] (get d 1)))))
        min (d3/min series (fn [d] (d3/min d (fn [d] (get d 1)))))
        height 500
        marginBottom 20
        marginTop 10]
    (-> d3
        .scaleLinear
        (.domain [0 max])
        (.rangeRound [(- height marginBottom) marginTop]))))

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
        y (y-scale series)
        height 500
        marginBottom 20
        marginLeft 40
        month-index (make-index data :month)
        div (-> d3
                (.select "body")
                (.append "div")
                (.attr "class" "tooltip-barchart")
                (.style "opacity" "0"))
        svg (-> d3
                (.select "#mychart")
                (.append "svg")
                (.attr "width" 900)
                (.attr "height" 600)
                (.attr "viewBox" (clj->js [0 0 900 600]))
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
                (.attr "y" (fn [d] (-> d second y)))
                (.attr "width" (.bandwidth x))
                (.attr "height" (fn [d] (- (-> d first y) (-> d second y))))
                (.on "mouseover" (fn [event d]
                                   (let [key (g/get d "key")
                                         category-name (->> key (get category-map) :name)
                                         category-amount (-> d (g/get "data") (get 1) (.get key) :amount)
                                         tooltip-text (str category-name " - " category-amount)]
                                     (this-as this (-> d3 (.select this)
                                                       (.transition)
                                                       (.duration "50")
                                                       (.attr "opacity" ".85")))
                                     (show-tooltip-label "div.tooltip-barchart" "1")
                                     (position-tooltip-label "div.tooltip-barchart" event tooltip-text))
                                   
                                   ))
                (.on "mouseout" (fn [d i] (this-as this (-> d3 (.select this)
                                                            (.transition)
                                                            (.duration "50")
                                                            (.attr "opacity" "1")))
                                  (show-tooltip-label "div.tooltip-barchart" "0")))
                (.on "click" (fn [event d] (let [category (->> (g/get d "key") (get category-map) :name)
                                                 month (-> d (g/get "data") first)
                                                 sub-period (date/date-label->period month period)]
                                             (show-tooltip-label "div.tooltip-barchart" "0")
                                             (dispatch [:navigate [sub-period :table [category]]]))))
                )
        svg2 (-> d3
                 (.select "#mychart svg")
                 ; horizontal axis
                 (.append "g")
                 (.attr "transform" (str "translate(0," (- height marginBottom) ")"))
                 (.attr "fill" "currentColor")
                 (.call (-> d3 (.axisBottom x) (.tickSizeOuter 0)))
                 (.call (fn [g] (-> g (.selectAll ".domain") (.remove))))
                 (.selectAll ".tick")
                 (.data (.domain x))
                 (.on "mouseover" (fn [event d]
                                    (let [tooltip-text (str d " - " (->> (get month-index d)
                                                                         (map :amount)
                                                                         (apply +)))]
                                      (show-tooltip-label "div.tooltip-barchart" "1")
                                      (position-tooltip-label "div.tooltip-barchart" event tooltip-text))))
                 (.on "mouseout" (fn [d i] (show-tooltip-label "div.tooltip-barchart" "0")))
                 (.on "click" (fn [event d] (let [month d
                                                  period (date/month-label->period month)]
                                              (show-tooltip-label "div.tooltip-barchart" "0")
                                              (dispatch [:navigate [period nil nil]]))))
                 )
        svg3 (-> d3
                 (.select "#mychart svg")
                 (.append "g")
                 (.attr "transform" (str "translate(" marginLeft ",0)"))
                 (.call (-> d3 (.axisLeft y) (.ticks nil "s")))
                 (.call (fn [g] (-> g (.selectAll ".domain") (.remove))))
                 )
        ;; color-svg (.assign js/Object (.node svg) (clj->js {"scales" color}))
        ]
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
      (< days 732) :2years
      :else :5years)))

(defn add-uncategorized-ids [transaction]
  (cond
      (and (-> transaction :category-id nil?)
           (-> transaction :amount pos?)) (assoc transaction :category-id "ukategorisert-in")
      (and (-> transaction :category-id nil?)
           (-> transaction :amount neg?)) (assoc transaction :category-id "ukategorisert-out")
      :else transaction))

(defn period-transactions->data [period-transactions period]
  (let [period-length (period-length period)
        label-fx (case period-length
                   :month date/get-date-label
                   :year date/get-month-label
                   :else date/get-month-label)]
    (->> period-transactions
         (map add-uncategorized-ids)
         (group-by #(-> % :date label-fx))
         (seq)
         (mapcat sum-month)
         (map #(update % :amount (fn [a] (- a)))))))

(defn draw-stacked-barchart [transactions categories period]
  (let [category-map (into {} (map (juxt :id #(identity %))
                                   (-> categories
                                       (conj {:id "ukategorisert-in" :name "ukategorisert-in"})
                                       (conj {:id "ukategorisert-out" :name "ukategorisert-out"}))))
        data (period-transactions->data transactions period)
        _ (println "draw-stacked-barchart: " data)
        _ (.log js/console data)
        color-map (-> (->> categories
                              (map #(-> % (select-keys [:id :color]) vals))
                              (map #(apply hash-map %))
                              (reduce merge))
                      (assoc "ukategorisert-in" "#ddd")
                      (assoc "ukategorisert-out" "#edd"))
        _ (println "draw-stacked-barchart: color-map " color-map)
        series (make-d3-series data)
        x (x-scale data)
        y (y-scale series)
        color (make-colors series color-map)
        chart (make-chart data series color category-map period)]
    ;;  (println color-map)
    ;;  (.log js/console x)
    ))