(ns client.components.period-selector-v2.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [client.services.date-service :as date]))

(def month-labels   ["Jan" "Feb" "Mar" "Apr" "Mai" "Jun" "Jul" "Aug" "Sep" "Okt" "Nov" "Des"])
(def quarter-labels ["1. Kvartal" "2. Kvartal" "3. Kvartal" "4. Kvartal"])

;; ----- period helpers ---------------------------------------------------

(defn cell->linear [year month] (+ (* year 12) month))

(defn quarter-of         [month] (quot month 3))
(defn quarter-start-month [q]    (* 3 q))

(defn month-selected? [year month period]
  (when period
    (let [cell-start (.getTime (js/Date. year month 1))
          p-start (.getTime (:start period))
          p-end (.getTime (:end period))]
      (and (>= cell-start p-start) (< cell-start p-end)))))

(defn quarter-selected? [year q period]
  (when period
    (let [qs (quarter-start-month q)
          cell-start (.getTime (js/Date. year qs 1))
          p-start (.getTime (:start period))
          p-end (.getTime (:end period))]
      (and (>= cell-start p-start) (< cell-start p-end)))))

(defn year-fully-selected? [year period]
  (when period
    (let [year-start (.getTime (js/Date. year 0 1))
          year-end (.getTime (js/Date. (inc year) 0 1))
          p-start (.getTime (:start period))
          p-end (.getTime (:end period))]
      (and (<= p-start year-start) (>= p-end year-end)))))

(defn selected-years-from-period [period]
  (when period
    (let [start-year (.getFullYear (:start period))
          end-date   (:end period)
          end-year   (if (and (= 0 (.getMonth end-date))
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
     :end   (js/Date. max-y (inc max-m) 1)
     :period-type (if (and (= min-y max-y) (= min-m max-m)) :month :months)}))

(defn make-quarter-period
  "Single-quarter period (3 months) for the quarter containing `month`."
  [year month]
  (let [qs (quarter-start-month (quarter-of month))]
    {:start (js/Date. year qs 1)
     :end   (js/Date. year (+ qs 3) 1)
     :period-type :quarter}))

(defn make-quarter-range-period [start-cell end-cell]
  (let [[sy sm] start-cell
        [ey em] end-cell
        s-idx (cell->linear sy (quarter-start-month (quarter-of sm)))
        e-idx (cell->linear ey (quarter-start-month (quarter-of em)))
        [min-y min-q] (if (<= s-idx e-idx) [sy (quarter-of sm)] [ey (quarter-of em)])
        [max-y max-q] (if (<= s-idx e-idx) [ey (quarter-of em)] [sy (quarter-of sm)])]
    {:start (js/Date. min-y (quarter-start-month min-q) 1)
     :end   (js/Date. max-y (+ (quarter-start-month max-q) 3) 1)
     :period-type (if (and (= min-y max-y) (= min-q max-q)) :quarter :quarters)}))

(defn make-year-cell-period [year]
  {:start (js/Date. year 0 1)
   :end   (js/Date. (inc year) 0 1)
   :period-type :year})

(defn make-year-range-period [start-year end-year]
  (let [min-y (min start-year end-year)
        max-y (max start-year end-year)]
    {:start (js/Date. min-y 0 1)
     :end   (js/Date. (inc max-y) 0 1)
     :period-type (if (= min-y max-y) :year :years)}))

;; ----- drag-state -------------------------------------------------------
;;
;; drag-state: {:dragging? :start :current :cell-kind :mode}
;;   cell-kind = :month | :quarter | :year — chosen at drag start
;;   start/current = a normalized cell id depending on cell-kind:
;;     :month   → [year month]    (month 0..11)
;;     :quarter → [year q]        (q 0..3)
;;     :year    → year            (just a number)

(def initial-drag-state {:dragging? false :start nil :current nil :cell-kind nil :mode nil})

(defn- cell-in-drag-range?
  "True when (kind, id) lies between drag start and drag current."
  [kind id {:keys [dragging? start current cell-kind]}]
  (and dragging? current (= cell-kind kind)
       (case kind
         :month   (let [[sy sm] start [cy cm] current [iy im] id
                        s (cell->linear sy sm) c (cell->linear cy cm) i (cell->linear iy im)]
                    (<= (min s c) i (max s c)))
         :quarter (let [[sy sq] start [cy cq] current [iy iq] id
                        s (+ (* sy 4) sq) c (+ (* cy 4) cq) i (+ (* iy 4) iq)]
                    (<= (min s c) i (max s c)))
         :year    (<= (min start current) id (max start current))
         false)))

(defn- finalize-drag! [drag-state on-change]
  (let [{:keys [start current cell-kind]} @drag-state
        period (when (and start current)
                 (case cell-kind
                   :month   (make-month-range-period start current)
                   :quarter (make-quarter-range-period
                              [(first start) (quarter-start-month (second start))]
                              [(first current) (quarter-start-month (second current))])
                   :year    (make-year-range-period start current)
                   nil))]
    (when period (on-change period))
    (reset! drag-state initial-drag-state)))

(defn- period-type->mode [pt]
  (case pt
    :year     :year
    :years    :year
    :quarter  :quarter
    :quarters :quarter
    :month))

;; ----- cell rendering ---------------------------------------------------

(defn- cell-classes
  "Returns the class string for a period cell given selected+neighbour state."
  [selected? prev-selected? next-selected?]
  (cond-> ""
    selected?                          (str " is-active")
    (and selected? (not prev-selected?)) (str " is-first")
    (and selected? (not next-selected?)) (str " is-last")))

(defn- month-cell
  "A single month button. extra-class for sizing (.period-month / .period-month-grid)."
  [{:keys [year month period drag-state mode dropdown-open? on-change extra-class label]}]
  (let [ds @drag-state
        sel?  (or (month-selected? year month period)
                  (cell-in-drag-range? :month [year month] ds))
        prev? (or (month-selected? year (dec month) period)
                  (cell-in-drag-range? :month [year (dec month)] ds))
        next? (or (month-selected? year (inc month) period)
                  (cell-in-drag-range? :month [year (inc month)] ds))]
    [:button
     {:class (str "period-month " extra-class (cell-classes sel? prev? next?))
      :on-mouse-down (fn [e]
                       (.preventDefault e)
                       (reset! drag-state
                               (merge initial-drag-state
                                      {:dragging? true :cell-kind :month :mode mode
                                       :start [year month] :current [year month]})))
      :on-mouse-enter (fn [_]
                        (let [d @drag-state]
                          (when (and (:dragging? d) (= (:cell-kind d) :month))
                            (swap! drag-state assoc :current [year month]))))
      :on-click (fn [_]
                  (when-not (:dragging? @drag-state)
                    (case mode
                      :year    (on-change (make-year-cell-period year))
                      :quarter (on-change (make-quarter-period year month))
                      (on-change (make-month-range-period [year month] [year month])))
                    (when dropdown-open? (reset! dropdown-open? false))))}
     (or label (get month-labels month))]))

(defn- quarter-cell
  [{:keys [year q period drag-state mode on-change]}]
  (let [ds @drag-state
        sel?  (or (quarter-selected? year q period)
                  (cell-in-drag-range? :quarter [year q] ds))
        prev? (or (quarter-selected? year (dec q) period)
                  (cell-in-drag-range? :quarter [year (dec q)] ds))
        next? (or (quarter-selected? year (inc q) period)
                  (cell-in-drag-range? :quarter [year (inc q)] ds))]
    [:button
     {:class (str "period-month period-quarter" (cell-classes sel? prev? next?))
      :on-mouse-down (fn [e]
                       (.preventDefault e)
                       (reset! drag-state
                               (merge initial-drag-state
                                      {:dragging? true :cell-kind :quarter :mode mode
                                       :start [year q] :current [year q]})))
      :on-mouse-enter (fn [_]
                        (let [d @drag-state]
                          (when (and (:dragging? d) (= (:cell-kind d) :quarter))
                            (swap! drag-state assoc :current [year q]))))
      :on-click (fn [_]
                  (when-not (:dragging? @drag-state)
                    (on-change (make-quarter-period year (quarter-start-month q)))))}
     (get quarter-labels q)]))

(defn- year-cell
  [{:keys [year years-list period drag-state mode on-change]}]
  (let [ds @drag-state
        idx (.indexOf (clj->js years-list) year)
        left-year  (when (pos? idx) (nth years-list (dec idx) nil))
        right-year (when (and (>= idx 0) (< idx (dec (count years-list))))
                     (nth years-list (inc idx) nil))
        sel?  (or (year-fully-selected? year period)
                  (cell-in-drag-range? :year year ds))
        left-sel?  (and left-year
                        (or (year-fully-selected? left-year period)
                            (cell-in-drag-range? :year left-year ds)))
        right-sel? (and right-year
                        (or (year-fully-selected? right-year period)
                            (cell-in-drag-range? :year right-year ds)))]
    [:button
     {:class (str "period-month period-year-cell" (cell-classes sel? left-sel? right-sel?))
      :on-mouse-down (fn [e]
                       (.preventDefault e)
                       (reset! drag-state
                               (merge initial-drag-state
                                      {:dragging? true :cell-kind :year :mode mode
                                       :start year :current year})))
      :on-mouse-enter (fn [_]
                        (let [d @drag-state]
                          (when (and (:dragging? d) (= (:cell-kind d) :year))
                            (swap! drag-state assoc :current year))))
      :on-click (fn [_]
                  (when-not (:dragging? @drag-state)
                    (on-change (make-year-cell-period year))))}
     (str year)]))

;; ----- dropdown panel ---------------------------------------------------

(defn- dropdown-panel
  [{:keys [years period drag-state mode on-change dropdown-open?]}]
  [:div.period-dropdown
   {:on-mouse-down #(.stopPropagation %)}
   [:table.period-grid
    [:tbody
     (for [year years]
       ^{:key year}
       [:tr
        [:td.period-grid-year
         {:on-click (fn [_]
                      (on-change (make-year-cell-period year))
                      (reset! dropdown-open? false))}
         (str year)]
        (for [m (range 12)]
          ^{:key m}
          [:td.period-grid-month
           [month-cell {:year year :month m :period period
                        :drag-state drag-state :mode mode
                        :dropdown-open? dropdown-open?
                        :on-change on-change
                        :extra-class "period-month-grid"}]])])]]])

;; ----- main component ---------------------------------------------------

(defn period-selector
  "Period selector. Opts:
     :period-sub - subscription key for the period (default :period)
     :on-change  - callback (fn [period]) (default: dispatch [:navigate [period nil nil]])"
  [& [{:keys [period-sub on-change]
       :or {period-sub :period
            on-change  (fn [p] (dispatch [:navigate [p nil nil]]))}}]]
  (let [drag-state    (r/atom initial-drag-state)
        mode          (r/atom (period-type->mode (:period-type @(subscribe [period-sub]))))
        dropdown-open? (r/atom false)
        root-el       (r/atom nil)
        on-global-mouseup
        (fn [_]
          (when (:dragging? @drag-state)
            (let [closed-by-drag? (some? (:dragging? @drag-state))]
              (finalize-drag! drag-state on-change)
              (when (and closed-by-drag? @dropdown-open?)
                (reset! dropdown-open? false)))))
        on-doc-mousedown
        (fn [e]
          (when (and @dropdown-open?
                     @root-el
                     (not (.contains @root-el (.-target e))))
            (reset! dropdown-open? false)))
        on-key-down
        (fn [e]
          (when (and @dropdown-open? (= "Escape" (.-key e)))
            (reset! dropdown-open? false)))]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (.addEventListener js/document "mouseup" on-global-mouseup)
        (.addEventListener js/document "mousedown" on-doc-mousedown)
        (.addEventListener js/document "keydown" on-key-down))
      :component-will-unmount
      (fn [_]
        (.removeEventListener js/document "mouseup" on-global-mouseup)
        (.removeEventListener js/document "mousedown" on-doc-mousedown)
        (.removeEventListener js/document "keydown" on-key-down))
      :reagent-render
      (fn [& _]
        (let [period      @(subscribe [period-sub])
              cur-mode    @mode
              open?       @dropdown-open?
              transaction-years @(subscribe [:transaction-years])
              current-year-num (js/parseInt (date/current-year))
              all-years   (vec (sort > (or (seq transaction-years) [current-year-num])))
              years-from-period (or (selected-years-from-period period) [current-year-num])
              focus-year  (first years-from-period)
              focus-month (when (:start period) (.getMonth (:start period)))
              focus-q     (when focus-month (quarter-of focus-month))
              on-mode-click
              (fn [k]
                (reset! mode k)
                (case k
                  :year    (on-change (make-year-cell-period focus-year))
                  :quarter (on-change (make-quarter-period focus-year (or focus-month 0)))
                  :month   (when focus-month
                             (on-change (make-month-range-period
                                          [focus-year focus-month]
                                          [focus-year focus-month])))))]
          [:div.period-sel
           {:ref #(reset! root-el %)
            :style (when (:dragging? @drag-state)
                     {:user-select "none" :cursor "grabbing"})}
           (case cur-mode
             :year
             ;; Flat row: cells are years
             [:div.period-months
              (for [y all-years]
                ^{:key y}
                [year-cell {:year y :years-list all-years :period period
                            :drag-state drag-state :mode cur-mode
                            :on-change on-change}])]

             ;; :month or :quarter — one row per affected year. The whole year
             ;; column acts as a single button that toggles the dropdown; each
             ;; year sits on its own line inside.
             [:<>
              [:button.period-year-stack
               {:class (when open? "is-open")
                :on-click #(swap! dropdown-open? not)}
               (for [[idx y] (map-indexed vector years-from-period)]
                 ^{:key y}
                 [:span.period-year-label
                  (str y)
                  (when (zero? idx) [:span.period-caret " ▾"])])]
              [:div.period-cells-stack
               (for [y years-from-period]
                 ^{:key y}
                 [:div.period-months
                  (case cur-mode
                    :quarter
                    (for [q (range 4)]
                      ^{:key q}
                      [quarter-cell {:year y :q q :period period
                                     :drag-state drag-state :mode cur-mode
                                     :on-change on-change}])
                    (for [m (range 12)]
                      ^{:key m}
                      [month-cell {:year y :month m :period period
                                   :drag-state drag-state :mode cur-mode
                                   :on-change on-change
                                   :extra-class ""}]))])]])
           [:div.period-types
            (for [[k label] [[:month "Måned"] [:quarter "Kvartal"] [:year "År"]]]
              ^{:key k}
              [:button.period-type
               {:class (when (= cur-mode k) "is-active")
                :on-click #(on-mode-click k)}
               label])]
           (when open?
             [dropdown-panel {:years all-years :period period
                              :drag-state drag-state :mode cur-mode
                              :on-change on-change
                              :dropdown-open? dropdown-open?}])]))})))
