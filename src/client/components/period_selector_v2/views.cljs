(ns client.components.period-selector-v2.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [client.services.date-service :as date]))

(def month-labels ["Jan" "Feb" "Mar" "Apr" "Mai" "Jun" "Jul" "Aug" "Sep" "Okt" "Nov" "Des"])

(defn cell->linear [year month]
  (+ (* year 12) month))

(defn month-in-drag-range? [year month {:keys [dragging? start current type]}]
  (and dragging?
       (= type :month)
       (some? current)
       (let [[sy sm] start
             [cy cm] current
             s-idx (cell->linear sy sm)
             c-idx (cell->linear cy cm)
             idx (cell->linear year month)]
         (<= (min s-idx c-idx) idx (max s-idx c-idx)))))

(defn year-in-drag-range? [year {:keys [dragging? start current type]}]
  (and dragging?
       (= type :year)
       (some? current)
       (let [sy (first start)
             cy (first current)]
         (<= (min sy cy) year (max sy cy)))))

(defn month-selected? [year month period]
  (when period
    (let [cell-start (.getTime (js/Date. year month 1))
          p-start (.getTime (:start period))
          p-end (.getTime (:end period))]
      (and (>= cell-start p-start)
           (< cell-start p-end)))))

(defn year-fully-selected? [year period]
  (when period
    (let [year-start (.getTime (js/Date. year 0 1))
          year-end (.getTime (js/Date. (inc year) 0 1))
          p-start (.getTime (:start period))
          p-end (.getTime (:end period))]
      (and (<= p-start year-start)
           (>= p-end year-end)))))

(defn selected-years-from-period [period]
  (when period
    (let [start-year (.getFullYear (:start period))
          end-date (:end period)
          end-year (if (and (= 0 (.getMonth end-date))
                            (= 1 (.getDate end-date)))
                     (dec (.getFullYear end-date))
                     (.getFullYear end-date))]
      (vec (range start-year (inc end-year))))))

(defn make-month-range-period [start-cell end-cell]
  (let [[sy sm] start-cell
        [ey em] end-cell
        s-idx (cell->linear sy sm)
        e-idx (cell->linear ey em)
        [min-y min-m] (if (<= s-idx e-idx) [sy sm] [ey em])
        [max-y max-m] (if (<= s-idx e-idx) [ey em] [sy sm])]
    {:start (js/Date. min-y min-m 1)
     :end (js/Date. max-y (inc max-m) 1)
     :period-type (if (and (= min-y max-y) (= min-m max-m)) :month :months)}))

(defn make-year-range-period [start-year end-year]
  (let [min-y (min start-year end-year)
        max-y (max start-year end-year)]
    {:start (js/Date. min-y 0 1)
     :end (js/Date. (inc max-y) 0 1)
     :period-type :year}))

(defn finalize-drag! [drag-state on-change]
  (let [{:keys [start current type]} @drag-state
        period (when (and start current)
                 (case type
                   :month (make-month-range-period start current)
                   :year (make-year-range-period (first start) (first current))
                   nil))]
    (when period
      (on-change period))
    (reset! drag-state {:dragging? false :start nil :current nil :type nil})))

(def initial-drag-state {:dragging? false :start nil :current nil :type nil})

(defn period-selector
  "Period selector component. Accepts an optional map:
     :period-sub     - subscription key for the period (default :period)
     :on-change      - callback (fn [period]) when a new period is selected
                       (default: dispatch [:navigate [period nil nil]])"
  [& [{:keys [period-sub on-change]
       :or {period-sub :period
            on-change (fn [p] (dispatch [:navigate [p nil nil]]))}}]]
  (let [expanded? (r/atom false)
        drag-state (r/atom initial-drag-state)
        on-change-fn on-change
        on-global-mouseup (fn [_]
                            (when (:dragging? @drag-state)
                              (finalize-drag! drag-state on-change-fn)))]
    (r/create-class
     {:component-did-mount
      (fn [_] (.addEventListener js/document "mouseup" on-global-mouseup))
      :component-will-unmount
      (fn [_] (.removeEventListener js/document "mouseup" on-global-mouseup))
      :reagent-render
      (fn [& _]
        (let [period @(subscribe [period-sub])
              transaction-years @(subscribe [:transaction-years])
              ds @drag-state
              current-year-num (js/parseInt (date/current-year))
              all-years (if (seq transaction-years)
                          (vec transaction-years)
                          [current-year-num])
              period-years (or (selected-years-from-period period) [current-year-num])
              visible-years (if @expanded?
                              all-years
                              (let [relevant (filterv (set all-years) period-years)]
                                (if (seq relevant) relevant [current-year-num])))
              scrollable? (and @expanded? (> (count all-years) 10))]
          [:div {:style {:user-select (if (:dragging? ds) "none" "auto")}}
           [:div {:style (merge {:display "inline-block"
                                 :border "1px solid #ccc"
                                 :border-radius "4px"
                                 :overflow "hidden"}
                                (when scrollable?
                                  {:max-height "320px"
                                   :overflow-y "auto"}))}
            [:table {:style {:border-collapse "collapse"
                             :font-size "13px"}}
             [:tbody
              (doall
               (for [year visible-years]
                 ^{:key year}
                 [:tr
                  [:td {:style {:padding "4px 10px"
                                :font-weight "bold"
                                :background-color (cond
                                                    (year-in-drag-range? year ds) "#aaf"
                                                    (year-fully-selected? year period) "#88f"
                                                    :else "#e8e8e8")
                                :border-right "2px solid #999"
                                :cursor "pointer"
                                :min-width "50px"
                                :text-align "center"}
                        :on-mouse-down (fn [e]
                                         (.preventDefault e)
                                         (reset! drag-state {:dragging? true
                                                             :start [year]
                                                             :current [year]
                                                             :type :year}))
                        :on-mouse-enter (fn [_]
                                          (let [d @drag-state]
                                            (when (and (:dragging? d) (= (:type d) :year))
                                              (swap! drag-state assoc :current [year]))))}
                   (str year)]
                  (doall
                   (for [month (range 12)]
                     ^{:key month}
                     [:td {:style {:padding "4px 8px"
                                   :cursor "pointer"
                                   :text-align "center"
                                   :min-width "36px"
                                   :background-color (cond
                                                       (month-in-drag-range? year month ds) "#aaf"
                                                       (month-selected? year month period) "#88f"
                                                       :else "#fff")
                                   :border-left "1px solid #eee"}
                           :on-mouse-down (fn [e]
                                            (.preventDefault e)
                                            (reset! drag-state {:dragging? true
                                                                :start [year month]
                                                                :current [year month]
                                                                :type :month}))
                           :on-mouse-enter (fn [_]
                                             (let [d @drag-state]
                                               (when (and (:dragging? d) (= (:type d) :month))
                                                 (swap! drag-state assoc :current [year month]))))}
                      (get month-labels month)]))
                  ]
                 ))
              ]
             ]
            ]
           (when (> (count all-years) 1)
             [:div {:style {:display "inline-block"
                            :margin-left "4px"
                            :cursor "pointer"
                            :font-size "12px"
                            :color "#666"
                            :vertical-align "top"
                            :padding-top "2px"}
                    :on-click #(swap! expanded? not)}
              (if @expanded? "\u25B2" "\u25BC")])
           ]))})))
