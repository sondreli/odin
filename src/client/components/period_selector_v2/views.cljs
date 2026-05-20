(ns client.components.period-selector-v2.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [client.services.date-service :as date]))

(def month-labels ["Jan" "Feb" "Mar" "Apr" "Mai" "Jun" "Jul" "Aug" "Sep" "Okt" "Nov" "Des"])

(defn cell->linear [year month]
  (+ (* year 12) month))

(defn- cell-in-drag-range?
  "True when this cell — month-cell (year, month) or year-cell (year, nil) —
   should highlight while a drag is in progress, given the mode."
  [year month mode {:keys [dragging? start current type]}]
  (and dragging?
       (some? current)
       (cond
         (= type :year)
         (let [sy (first start) cy (first current)]
           (<= (min sy cy) year (max sy cy)))

         (= type :month)
         (let [[sy sm] start [cy cm] current]
           (case mode
             :quarter
             (let [s-idx (cell->linear sy (quarter-start-month (quarter-of sm)))
                   c-idx (cell->linear cy (quarter-start-month (quarter-of cm)))]
               (if (some? month)
                 (let [q-start-idx (cell->linear year (quarter-start-month (quarter-of month)))]
                   (<= (min s-idx c-idx) q-start-idx (max s-idx c-idx)))
                 false))

             :year
             (<= (min sy cy) year (max sy cy))

             (when (some? month)
               (let [s-idx (cell->linear sy sm)
                     c-idx (cell->linear cy cm)
                     idx (cell->linear year month)]
                 (<= (min s-idx c-idx) idx (max s-idx c-idx))))))

         :else false)))

(defn month-in-drag-range? [year month mode ds]
  (cell-in-drag-range? year month mode ds))

(defn year-in-drag-range? [year mode ds]
  (cell-in-drag-range? year nil mode ds))

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

(defn quarter-of [month] (quot month 3))
(defn quarter-start-month [q] (* 3 q))

(defn make-quarter-period
  "Single-quarter period (3 months) for the quarter containing `month`."
  [year month]
  (let [qs (quarter-start-month (quarter-of month))]
    {:start (js/Date. year qs 1)
     :end (js/Date. year (+ qs 3) 1)
     :period-type :quarter}))

(defn make-quarter-range-period
  "Multi-quarter range, snapped to quarter boundaries. :quarter if single, :quarters otherwise."
  [start-cell end-cell]
  (let [[sy sm] start-cell
        [ey em] end-cell
        s-idx (cell->linear sy (quarter-start-month (quarter-of sm)))
        e-idx (cell->linear ey (quarter-start-month (quarter-of em)))
        [min-y min-q] (if (<= s-idx e-idx)
                        [sy (quarter-of sm)]
                        [ey (quarter-of em)])
        [max-y max-q] (if (<= s-idx e-idx)
                        [ey (quarter-of em)]
                        [sy (quarter-of sm)])]
    {:start (js/Date. min-y (quarter-start-month min-q) 1)
     :end (js/Date. max-y (+ (quarter-start-month max-q) 3) 1)
     :period-type (if (and (= min-y max-y) (= min-q max-q)) :quarter :quarters)}))

(defn make-year-range-period [start-year end-year]
  (let [min-y (min start-year end-year)
        max-y (max start-year end-year)]
    {:start (js/Date. min-y 0 1)
     :end (js/Date. (inc max-y) 0 1)
     :period-type (if (= min-y max-y) :year :years)}))

(defn make-year-cell-period
  "Year period covering just the given year — for clicking a month/year cell in År mode."
  [year]
  {:start (js/Date. year 0 1)
   :end (js/Date. (inc year) 0 1)
   :period-type :year})

(defn finalize-drag! [drag-state on-change mode]
  (let [{:keys [start current type]} @drag-state
        period (when (and start current)
                 (case type
                   :month (case mode
                            :quarter (make-quarter-range-period start current)
                            :year    (make-year-range-period (first start) (first current))
                            (make-month-range-period start current))
                   :year (make-year-range-period (first start) (first current))
                   nil))]
    (when period
      (on-change period))
    (reset! drag-state {:dragging? false :start nil :current nil :type nil})))

(def initial-drag-state {:dragging? false :start nil :current nil :type nil})

(defn- period-type->mode [pt]
  (case pt
    :year     :year
    :years    :year
    :quarter  :quarter
    :quarters :quarter
    :month))

(defn period-selector
  "Period selector component. Accepts an optional map:
     :period-sub     - subscription key for the period (default :period)
     :on-change      - callback (fn [period]) when a new period is selected
                       (default: dispatch [:navigate [period nil nil]])"
  [& [{:keys [period-sub on-change]
       :or {period-sub :period
            on-change (fn [p] (dispatch [:navigate [p nil nil]]))}}]]
  (let [initial-mode (period-type->mode (:period-type @(subscribe [period-sub])))
        expanded? (r/atom false)
        drag-state (r/atom initial-drag-state)
        hovered-cell (r/atom nil)
        mode (r/atom initial-mode)
        on-change-fn on-change
        on-global-mouseup (fn [_]
                            (when (:dragging? @drag-state)
                              (finalize-drag! drag-state on-change-fn @mode)))]
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
          (let [hv @hovered-cell
                dragging? (:dragging? ds)
                cur-mode @mode
                mode-cell-style (fn [selected? last?]
                                  (cond-> {:padding "4px 12px"
                                           :font-size "13px"
                                           :background-color (if selected? "#4a6cf7" "#e8e8e8")
                                           :color (if selected? "#fff" "inherit")
                                           :cursor "pointer"
                                           :text-align "center"
                                           :min-width "60px"
                                           :user-select "none"
                                           :transition "background-color 0.1s ease"}
                                    (not last?) (assoc :border-right "1px solid #ccc")))
                mode-options [[:month   "Måned"]
                              [:quarter "Kvartal"]
                              [:year    "År"]]]
          [:div {:style {:user-select (if dragging? "none" "auto")
                         :cursor (if dragging? "grabbing" "auto")}}
           [:div {:style (merge {:display "inline-flex"
                                 :vertical-align "top"
                                 :align-items "stretch"
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
                  (let [is-selected? (year-fully-selected? year period)
                        is-dragged? (year-in-drag-range? year cur-mode ds)
                        is-hovered? (and (not dragging?) (= hv [:year year]))]
                  [:td {:style {:padding "4px 10px"
                                :font-weight "bold"
                                :background-color (cond
                                                    is-dragged? "#c7d2fe"
                                                    is-selected? "#4a6cf7"
                                                    is-hovered? "#d5d5d5"
                                                    :else "#e8e8e8")
                                :color (if is-selected? "#fff" "inherit")
                                :border-right "2px solid #999"
                                :cursor (if dragging? "grabbing" "grab")
                                :min-width "50px"
                                :text-align "center"
                                :transition "background-color 0.1s ease"}
                        :on-mouse-down (fn [e]
                                         (.preventDefault e)
                                         (reset! drag-state {:dragging? true
                                                             :start [year]
                                                             :current [year]
                                                             :type :year}))
                        :on-mouse-enter (fn [_]
                                          (reset! hovered-cell [:year year])
                                          (let [d @drag-state]
                                            (when (and (:dragging? d) (= (:type d) :year))
                                              (swap! drag-state assoc :current [year]))))
                        :on-mouse-leave #(when (= @hovered-cell [:year year])
                                           (reset! hovered-cell nil))}
                   (str year)])
                  (doall
                   (for [month (range 12)]
                     (let [is-selected? (month-selected? year month period)
                           is-dragged? (month-in-drag-range? year month cur-mode ds)
                           is-hovered? (and (not dragging?) (= hv [:month year month]))]
                     ^{:key month}
                     [:td {:style {:padding "4px 8px"
                                   :cursor (if dragging? "grabbing" "grab")
                                   :text-align "center"
                                   :min-width "36px"
                                   :background-color (cond
                                                       is-dragged? "#c7d2fe"
                                                       is-selected? "#4a6cf7"
                                                       is-hovered? "#f0f0f0"
                                                       :else "#fff")
                                   :color (if is-selected? "#fff" "inherit")
                                   :border-left "1px solid #eee"
                                   :transition "background-color 0.1s ease"}
                           :on-mouse-down (fn [e]
                                            (.preventDefault e)
                                            (reset! drag-state {:dragging? true
                                                                :start [year month]
                                                                :current [year month]
                                                                :type :month}))
                           :on-mouse-enter (fn [_]
                                             (reset! hovered-cell [:month year month])
                                             (let [d @drag-state]
                                               (when (and (:dragging? d) (= (:type d) :month))
                                                 (swap! drag-state assoc :current [year month]))))
                           :on-mouse-leave #(when (= @hovered-cell [:month year month])
                                              (reset! hovered-cell nil))}
                      (get month-labels month)])))
                  ]
                 ))
              ]
             ]
            [:div {:style {:display "inline-flex"
                           :border-left "1px solid #ccc"}}
             (for [[i [opt-key label]] (map-indexed vector mode-options)
                   :let [selected? (= cur-mode opt-key)
                         last? (= i (dec (count mode-options)))]]
               ^{:key opt-key}
               [:div {:on-click #(reset! mode opt-key)
                      :style (mode-cell-style selected? last?)}
                label])]
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
           ])))})))
