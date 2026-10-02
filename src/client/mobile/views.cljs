(ns client.mobile.views
  "Mobile-specific UI. Shows transactions for the current period using React Native components."
  (:require [re-frame.core :refer [subscribe dispatch]]
            [reagent.core :as r]
            [clojure.string :as s]
            [client.services.date-service :as date]
            [client.components.treemap-component.layout :as layout]
            [client.services.pace-service :as pace]
            [client.components.summed-table-component.views :as summed]
            [client.services.chart-service :as chart-svc]
            [common.category-service :as category]
            [client.services.color-service :as color]
            [goog.string :as gstring]
            [goog.string.format]
            [goog.object :as gobj])
  (:require ["react-native" :refer [View Text ScrollView StyleSheet FlatList TextInput
                                    TouchableOpacity Dimensions RefreshControl]]
            ["react-native-gifted-charts" :refer [BarChart]]))

(def screen-height (.-height (.get Dimensions "window")))
(def screen-width (.-width (.get Dimensions "window")))

(def styles
  (StyleSheet.create
   #js {:row #js {:flexDirection "row"
                  :justifyContent "space-between"
                  :alignItems "center"
                  :paddingVertical 12
                  :paddingHorizontal 12
                  :backgroundColor "#fff"
                  :marginBottom 6
                  :borderRadius 8
                  :shadowColor "#000"
                  :shadowOffset #js {:width 0 :height 1}
                  :shadowOpacity 0.05
                  :shadowRadius 2
                  :elevation 2}
        :rowDate #js {:fontSize 12
                      :color "#666"
                      :marginRight 8}
        :rowDesc #js {:flex 1
                     :fontSize 14
                     :color "#333"}
        :rowAmount #js {:fontSize 14
                        :fontWeight "600"
                        :color "#333"}
        :rowAmountNegative #js {:fontSize 14
                                  :fontWeight "600"
                                  :color "#c00"}}))

(defn format-date [d]
  (cond
    (nil? d) ""
    (string? d) (-> d (s/split #"T") first)
    (instance? js/Date d) (let [y (.getFullYear d)
                                 m (inc (.getMonth d))
                                 day (.getDate d)]
                             (str y "-"
                                  (when (< m 10) "0") m "-"
                                  (when (< day 10) "0") day))
    (number? d) (format-date (js/Date. d))
    :else (str d)))

;; ---------- Mobile Treemap ----------

(defn- treemap-label [name pct min-dim]
  [:<>
   (when (> min-dim 28)
     [:> Text {:style #js {:color "#fff"
                            :fontSize (cond (> min-dim 80) 14 (> min-dim 50) 12 :else 10)
                            :fontWeight "600"
                            :textAlign "center"
                            :textShadowColor "rgba(0,0,0,0.5)"
                            :textShadowOffset #js {:width 0 :height 1}
                            :textShadowRadius 3
                            :paddingHorizontal 4}
               :numberOfLines 1
               :ellipsizeMode "tail"}
      name])
   (when (and pct (> min-dim 44))
     [:> Text {:style #js {:color "rgba(255,255,255,0.9)"
                            :fontSize (if (> min-dim 80) 12 10)
                            :textAlign "center"
                            :textShadowColor "rgba(0,0,0,0.5)"
                            :textShadowOffset #js {:width 0 :height 1}
                            :textShadowRadius 3}}
      (gstring/format "%.1f%%" pct)])])

(defn- mobile-pace-marker [pace-marker horizontal?]
  (when-let [fraction (:fraction pace-marker)]
    [:<>
     (when (and (:band-low pace-marker) (:band-high pace-marker)
                (> (- (:band-high pace-marker) (:band-low pace-marker)) 0.004))
       [:> View {:pointerEvents "none"
                 :style (if horizontal?
                          #js {:position "absolute" :top 0 :bottom 0
                               :left (str (* 100 (:band-low pace-marker)) "%")
                               :width (str (* 100 (- (:band-high pace-marker) (:band-low pace-marker))) "%")
                               :backgroundColor "rgba(255,255,255,0.32)"}
                          #js {:position "absolute" :left 0 :right 0
                               :top (str (* 100 (:band-low pace-marker)) "%")
                               :height (str (* 100 (- (:band-high pace-marker) (:band-low pace-marker))) "%")
                               :backgroundColor "rgba(255,255,255,0.32)"})}])
     [:> View {:pointerEvents "none"
               :style (if horizontal?
                        #js {:position "absolute" :top 0 :bottom 0 :width 2
                             :marginLeft -1
                             :left (str (* 100 fraction) "%")
                             :backgroundColor "#fff"}
                        #js {:position "absolute" :left 0 :right 0 :height 2
                             :marginTop -1
                             :top (str (* 100 fraction) "%")
                             :backgroundColor "#fff"})}]]))

(defn- mobile-treemap-rect [{:keys [id name color value target pace-marker]} rect pct-base selected-name]
  (let [{ix :x iy :y iw :w ih :h} rect
        faded? (and selected-name (not= name selected-name))
        pct (when (and pct-base (pos? pct-base))
              (* 100 (/ value pct-base)))
        min-dim (min iw ih)
        base-color (or color "#9ca3af")
        cat-target (layout/parse-target target)
        show-split? (and cat-target (not= value cat-target))
        rect-value (max value (or cat-target 0))
        normal-ratio (if (and show-split? (pos? rect-value))
                       (/ (min value cat-target) rect-value)
                       1.0)
        horizontal? (>= iw ih)
        over-target? (and show-split? (> value cat-target))
        under-color (layout/lighten-color base-color 0.5)
        over-color (layout/darken-color base-color 0.6)]
    ^{:key id}
    [:> TouchableOpacity
     {:style #js {:position "absolute"
                  :left ix :top iy :width iw :height ih
                  :borderWidth 0.5
                  :borderColor "rgba(255,255,255,0.5)"
                  :overflow "hidden"
                  :opacity (if faded? 0.3 1)}
      :activeOpacity 0.7
      :onPress #(dispatch [:view-category name])}
     (if show-split?
       ;; Budget split view
       (let [normal-frac normal-ratio
             accent-frac (- 1 normal-ratio)]
         [:> View {:style #js {:flex 1 :flexDirection (if horizontal? "row" "column")}}
          [:> View {:style (if horizontal?
                             #js {:flex normal-frac :backgroundColor base-color}
                             #js {:flex normal-frac :backgroundColor base-color})}]
          [:> View {:style (if horizontal?
                             #js {:flex accent-frac :backgroundColor (if over-target? over-color under-color)}
                             #js {:flex accent-frac :backgroundColor (if over-target? over-color under-color)})}]])
       ;; Plain view
       [:> View {:style #js {:flex 1 :backgroundColor base-color}}])
     ;; Label overlay
     [:> View {:style #js {:position "absolute" :left 0 :top 0 :right 0 :bottom 0
                            :justifyContent "center" :alignItems "center"
                            :pointerEvents "none"}}
      [treemap-label name pct min-dim]]
     [mobile-pace-marker pace-marker horizontal?]]))

(defn mobile-treemap [below-height-atom & {:keys [show-budget?] :or {show-budget? true}}]
  (let [width-atom (r/atom nil)]
    (fn [below-height-atom & {:keys [show-budget?] :or {show-budget? true}}]
      (let [categories @(subscribe [:summed-categories])
            period @(subscribe [:period])
            filter-path @(subscribe [:filter-path])
            selected-name (when (= 1 (count filter-path)) (first filter-path))
            single-month? (and show-budget? (= :month (:period-type period)))
            now (js/Date.)
            current-month? (and single-month?
                                (:start period)
                                (= (.getFullYear (:start period)) (.getFullYear now))
                                (= (.getMonth (:start period)) (.getMonth now)))
            pace-now (when current-month? (pace/for-today @(subscribe [:pace-prediction])))
            below-h (or @below-height-atom 0)
            measured? (pos? below-h)
            treemap-height (/ screen-height 2)
            cw @width-atom
            cats (->> categories
                      (remove #(layout/excluded-ids (:id %)))
                      (filter #(or (neg? (:amount %))
                                   (and single-month? (layout/parse-target (:target %)))))
                      (map (fn [c]
                             (let [amt (Math/abs (:amount c))
                                   tgt (if show-budget? (layout/parse-target (:target c)) nil)]
                               (assoc c :value amt :target tgt))))
                      (filter #(pos? (if single-month?
                                       (max (:value %) (or (:target %) 0))
                                       (:value %))))
                      (sort-by #(if single-month?
                                  (max (:value %) (or (:target %) 0))
                                  (:value %))
                               >))
            total-value (reduce + 0
                                (map #(if single-month?
                                        (max (:value %) (or (:target %) 0))
                                        (:value %))
                                     cats))
            target-sum (when single-month?
                         (reduce + 0 (map #(or (:target %) 0) cats)))
            pct-base (if (and single-month? (pos? (or target-sum 0)))
                       target-sum
                       total-value)
            va (when (and cw (pos? total-value)) (* cw treemap-height))
            items (when va
                    (mapv (fn [c]
                            (let [effective (if single-month?
                                             (max (:value c) (or (:target c) 0))
                                             (:value c))
                                  marker (when (and pace-now (pos? (or (:target c) 0)))
                                           (when-let [pc (pace/category-pace pace-now (:id c))]
                                             (pace/tile-marker pc now effective
                                                               (:days-in-month pace-now))))]
                              (cond-> (assoc c :area (* va (/ effective total-value)))
                                marker (assoc :pace-marker marker))))
                          cats))
            laid-out (when (and items (seq items) cw (pos? cw))
                       (layout/squarify items {:x 0 :y 0 :w cw :h treemap-height}))]
        [:> View {:style #js {:height treemap-height :overflow "hidden"}
                  :onLayout (fn [^js e]
                              (let [w (-> e .-nativeEvent .-layout .-width)]
                                (when (and (pos? w) (not= w @width-atom))
                                  (reset! width-atom w))))}
         (when (and measured? laid-out)
           (doall
            (for [item laid-out]
              [mobile-treemap-rect item (:rect item) pct-base selected-name])))]))))

;; ---------- Spending Summary ----------

(defn spending-summary []
  (let [categories @(subscribe [:summed-categories])
        balance-data @(subscribe [:balance])
        balance (:available-balance balance-data)
        spent (->> categories
                   (remove #(layout/excluded-ids (:id %)))
                   (filter #(neg? (:amount %)))
                   (map :amount)
                   (reduce + 0)
                   Math/abs)]
    [:> View {:style #js {:flexDirection "row" :justifyContent "space-evenly"
                          :paddingVertical 12 :backgroundColor "#f5f5f5"}}
     [:> View {:style #js {:alignItems "center"}}
      [:> Text {:style #js {:fontSize 12 :color "#888" :marginBottom 2}} "Konto"]
      [:> Text {:style #js {:fontSize 20 :fontWeight "bold" :color "#333"}}
       (if balance (format-amount balance) "—")]]
     [:> View {:style #js {:alignItems "center"}}
      [:> Text {:style #js {:fontSize 12 :color "#888" :marginBottom 2}} "Brukt"]
      [:> Text {:style #js {:fontSize 20 :fontWeight "bold" :color "#333"}}
       (format-amount spent)]]]))

;; ---------- Budget Summary Bars ----------

(defn- format-amount [n]
  (when n
    (let [abs-n (Math/abs n)
          whole (Math/floor abs-n)
          s (.replace (.toFixed whole 0) (js/RegExp. "\\B(?=(\\d{3})+(?!\\d))" "g") " ")]
      (if (neg? n) (str "-" s) s))))

(defn budget-summary-bars []
  (let [categories @(subscribe [:summed-categories])
        period @(subscribe [:period])
        balance-data @(subscribe [:balance])
        filter-path @(subscribe [:filter-path])
        selected-name (when (= 1 (count filter-path)) (first filter-path))
        single-month? (= :month (:period-type period))
        all-cats (->> categories
                      (remove #(layout/excluded-ids (:id %)))
                      (filter #(or (neg? (:amount %))
                                   (and single-month? (layout/parse-target (:target %)))))
                      (map (fn [c]
                             (let [amt (Math/abs (:amount c))
                                   tgt (layout/parse-target (:target c))]
                               (assoc c :value amt :target tgt))))
                      (filter #(pos? (if single-month?
                                       (max (:value %) (or (:target %) 0))
                                       (:value %)))))
        cats (if selected-name
               (filter #(= (:name %) selected-name) all-cats)
               all-cats)
        budgeted (filter #(pos? (or (:target %) 0)) cats)
        unbudgeted (remove #(pos? (or (:target %) 0)) cats)
        target-sum (reduce + 0 (map :target budgeted))
        overuse (reduce + 0 (map #(max 0 (- (:value %) (:target %))) budgeted))
        spent (reduce + 0 (map #(min (:value %) (:target %)) budgeted))
        uncategorized (reduce + 0 (map :value unbudgeted))
        remaining (max 0 (- target-sum spent))
        total (+ spent overuse uncategorized remaining)
        balance (:available-balance balance-data)
        now (js/Date.)
        current-month? (and single-month?
                            (:start period)
                            (= (.getFullYear (:start period)) (.getFullYear now))
                            (= (.getMonth (:start period)) (.getMonth now)))
        pace-now (when current-month? (pace/for-today @(subscribe [:pace-prediction])))
        budgeted-ids (map :id budgeted)
        pace-expected (when pace-now (pace/aggregate-expected pace-now budgeted-ids now))
        pace-band (when pace-now (pace/aggregate-band pace-now budgeted-ids now))
        pace-frac (when (and pace-expected (pos? total))
                    (pace/marker-fraction pace-expected total))
        pace-low (when (and pace-band (pos? total))
                   (pace/marker-fraction (:low pace-band) total))
        pace-high (when (and pace-band (pos? total))
                    (pace/marker-fraction (:high pace-band) total))
        pace-note (when pace-now
                    (let [n (or (:days-in-month pace-now) (pace/days-in-month now))
                          used (+ spent overuse)
                          pct (if (pos? target-sum)
                                (Math/round (* 100 (/ used target-sum)))
                                0)]
                      (str "Dag " (.getDate now) " av " n " · " pct "% av budsjettet brukt")))
        selected-pace (when (and selected-name pace-now)
                        (when-let [cat (first budgeted)]
                          (pace/category-pace pace-now (:id cat))))
        selected-expected (when selected-pace
                            (pace/expected-at selected-pace now (:days-in-month pace-now)))]
    (when (and single-month? (pos? target-sum) (pos? total))
      (let [;; Order: Spent, Overspent, Uncategorized (no label), Remaining
            segments (cond-> [{:label "Brukt" :amount spent :color "#4a6cf7"
                               :pct (* 100 (/ spent total)) :text-color "#333"}]
                       (pos? overuse) (conj {:label "Overforbruk" :amount overuse :color "#ef4444"
                                             :pct (* 100 (/ overuse total)) :text-color "#c00"})
                       (pos? uncategorized) (conj {:label nil :amount uncategorized :color "#9ca3af"
                                                    :pct (* 100 (/ uncategorized total)) :text-color "#666"})
                       (pos? remaining) (conj {:label "Gjenstår" :amount remaining :color "#34d399"
                                               :pct (* 100 (/ remaining total)) :text-color "#333"}))
            ;; Determine text placement for each segment.
            ;; Default: right-aligned above bar.
            ;; If right-aligned text overflows into prev bar's text → move below.
            ;; Right-aligned text spans from (bar-right - text-est) to bar-right.
            ;; Previous bar's text spans from (prev-bar-right - text-est) to prev-bar-right
            ;; (or prev-bar-left to prev-bar-left + text-est if left-aligned).
            ;; Overlap = the two text spans intersect.
            text-est-pct 32 ;; ~120px on ~375px screen ≈ 32%
            placements (loop [i 0
                              cum 0
                              prev-text-right 0 ;; rightmost x% occupied by prev text
                              result []]
                         (if (>= i (count segments))
                           result
                           (let [seg (nth segments i)
                                 has-label? (some? (:label seg))
                                 left-pct cum
                                 bar-pct (:pct seg)
                                 bar-right (+ left-pct bar-pct)
                                 right-pct (- 100 bar-right)
                                 ;; Right-aligned text starts at (bar-right - text-est)
                                 text-left (if has-label? (max 0 (- bar-right text-est-pct)) bar-right)
                                 ;; Does right-aligned text overlap with prev bar's text?
                                 overlaps-prev? (and has-label? (pos? i) (< text-left prev-text-right))
                                 ;; If overlaps, move below
                                 text-below? overlaps-prev?
                                 ;; Track where this bar's text ends (rightmost x)
                                 ;; Unlabeled segments don't occupy text space
                                 my-text-right (cond
                                                 (not has-label?) prev-text-right
                                                 text-below? prev-text-right
                                                 :else (min 100 (max bar-right text-est-pct)))]
                             (recur (inc i)
                                    bar-right
                                    my-text-right
                                    (conj result {:left-pct left-pct
                                                  :bar-pct bar-pct
                                                  :right-pct right-pct
                                                  :text-below? text-below?})))))
            ;; For below-bar labels, compute which row they go on (same overlap logic)
            below-placements (loop [i 0
                                    rows [] ;; vec of {:right-edge pct} per row
                                    result []]
                               (if (>= i (count segments))
                                 result
                                 (let [seg (nth segments i)
                                       p (nth placements i)
                                       has-label? (some? (:label seg))]
                                   (if (or (not (:text-below? p)) (not has-label?))
                                     (recur (inc i) rows (conj result nil))
                                     ;; Find first row where text doesn't overlap
                                     (let [bar-right (+ (:left-pct p) (:bar-pct p))
                                           text-left (max 0 (- bar-right text-est-pct))
                                           row-idx (loop [r 0]
                                                     (if (>= r (count rows))
                                                       r ;; new row
                                                       (if (< text-left (nth rows r))
                                                         (recur (inc r)) ;; overlaps, try next row
                                                         r)))
                                           new-right (min 100 (max bar-right text-est-pct))
                                           rows (if (>= row-idx (count rows))
                                                  (conj rows new-right)
                                                  (assoc rows row-idx new-right))]
                                       (recur (inc i) rows (conj result row-idx))))))
                               )
            below-row-count (count (filter some? (distinct below-placements)))]
        [:> View {:style #js {:paddingTop 12 :paddingBottom 8 :backgroundColor "#f5f5f5"}}
         (when pace-note
           [:> Text {:style #js {:fontSize 12 :color "#666" :textAlign "center"
                                 :marginBottom 6 :paddingHorizontal 12}}
            pace-note])
         ;; Text labels above bars (single row, all absolutely positioned)
         [:> View {:style #js {:height 18}}
          (doall
           (for [[idx seg] (map-indexed vector segments)]
             (let [p (nth placements idx)]
               (when (and (:label seg) (not (:text-below? p)))
                 ^{:key (str "label-above-" idx)}
                 [:> View {:style #js {:position "absolute"
                                        :right (str (:right-pct p) "%")
                                        :bottom 0
                                        :flexDirection "row"
                                        :alignItems "baseline"}}
                  [:> Text {:style #js {:fontSize 11 :color "#888" :marginRight 4} :numberOfLines 1}
                   (:label seg)]
                  [:> Text {:style #js {:fontSize 14 :fontWeight "600" :color (:text-color seg)} :numberOfLines 1}
                   (format-amount (:amount seg))]]))))]
         ;; Continuous bar strip. The tick sits outside the clipped row.
         [:> View {:style #js {:position "relative"}}
          [:> View {:style #js {:flexDirection "row" :height 10 :borderRadius 5 :overflow "hidden"}}
           (doall
            (for [[idx seg] (map-indexed vector segments)]
              ^{:key (str "bar-" idx)}
              [:> View {:style #js {:flex (:pct seg) :backgroundColor (:color seg)}}]))]
          (when (and pace-low pace-high (> (- pace-high pace-low) 0.004))
            [:> View {:pointerEvents "none"
                      :style #js {:position "absolute" :top 0 :height 10
                                  :left (str (* 100 pace-low) "%")
                                  :width (str (* 100 (- pace-high pace-low)) "%")
                                  :backgroundColor "rgba(28,20,12,0.16)"}}])
          (when pace-frac
            [:> View {:pointerEvents "none"
                      :style #js {:position "absolute" :top -2 :bottom -2 :width 2
                                  :marginLeft -1
                                  :left (str (* 100 pace-frac) "%")
                                  :backgroundColor "#1c140c"}}])]
         (when (and selected-name (number? selected-expected))
           (let [cat (first budgeted)]
             [:> Text {:style #js {:fontSize 13 :color "#333" :textAlign "center"
                                   :marginTop 8 :paddingHorizontal 12}}
              (str (:name cat) ": " (format-amount (:value cat)) " av "
                   (format-amount (:target cat)) " kr, forventet "
                   (format-amount selected-expected) " kr i dag")]))
         ;; Text labels below bars (multiple rows if overlapping)
         (when (some some? below-placements)
           (let [max-row (apply max (filter some? below-placements))]
             (doall
              (for [row (range (inc max-row))]
                ^{:key (str "below-row-" row)}
                [:> View {:style #js {:height 18 :marginTop (if (zero? row) 2 0)}}
                 (doall
                  (for [[idx seg] (map-indexed vector segments)]
                    (let [bp (nth below-placements idx)
                          p (nth placements idx)]
                      (when (= bp row)
                        ^{:key (str "label-below-" idx)}
                        [:> View {:style #js {:position "absolute"
                                               :right (str (:right-pct p) "%")
                                               :top 0
                                               :flexDirection "row"
                                               :alignItems "baseline"}}
                         [:> Text {:style #js {:fontSize 11 :color "#888" :marginRight 4} :numberOfLines 1}
                          (:label seg)]
                         [:> Text {:style #js {:fontSize 14 :fontWeight "600" :color (:text-color seg)} :numberOfLines 1}
                          (format-amount (:amount seg))]]))))]))))
         ;; Account balance expression — only for current month
         (let [now (js/Date.)
               current-month? (and (= :month (:period-type period))
                                   (= (.getFullYear (:start period)) (.getFullYear now))
                                   (= (.getMonth (:start period)) (.getMonth now)))]
           (when current-month?
             [:> View {:style #js {:marginTop 10 :paddingTop 8 :paddingHorizontal 16
                                    :borderTopWidth 1 :borderTopColor "#e5e7eb"}}
              [:> View {:style #js {:flexDirection "row" :justifyContent "center" :alignItems "center"}}
               (if balance
                 [:> Text {:style #js {:fontSize 14 :color "#333"}}
                  (str (format-amount balance) " - " (format-amount remaining)
                       " = " (format-amount (- balance remaining)))]
                 [:> Text {:style #js {:fontSize 14 :color "#999"}}
                  (str "— - " (format-amount remaining) " = —")])]]))]))))

;; ---------- Period Selector ----------

(def month-labels ["Jan" "Feb" "Mar" "Apr" "Mai" "Jun" "Jul" "Aug" "Sep" "Okt" "Nov" "Des"])

(defn- month-selected? [year month period]
  (when period
    (let [cell-start (.getTime (js/Date. year month 1))
          p-start (.getTime (:start period))
          p-end (.getTime (:end period))]
      (and (>= cell-start p-start)
           (< cell-start p-end)))))

(defn- month-in-pending-range? [dy month [sy sm]]
  (let [cell-idx (+ (* dy 12) month)
        start-idx (+ (* sy 12) sm)]
    (and (>= cell-idx (min start-idx cell-idx))
         (<= cell-idx (max start-idx cell-idx)))))

(defn- year-fully-selected? [year period]
  (when period
    (let [year-start (.getTime (js/Date. year 0 1))
          year-end (.getTime (js/Date. (inc year) 0 1))
          p-start (.getTime (:start period))
          p-end (.getTime (:end period))]
      (and (<= p-start year-start)
           (>= p-end year-end)))))

(defn- month-button [year month period range-start expanded? displayed-year]
  (let [rs @range-start
        selected? (month-selected? year month period)
        is-range-start? (and rs (= rs [year month]))
        in-range? (and rs (not is-range-start?) (month-in-pending-range? year month rs))
        bg (cond
             is-range-start? "transparent"
             selected? "#4a6cf7"
             in-range? "#c7d2fe"
             :else "#e8e8e8")]
    [:> TouchableOpacity
     {:onPress (fn []
                 (reset! displayed-year year)
                 (reset! expanded? false)
                 (if rs
                   (if (= rs [year month])
                     (do (reset! range-start nil)
                         (dispatch [:set-period {:start (js/Date. year month 1)
                                                 :end (js/Date. year (inc month) 1)
                                                 :period-type :month}]))
                     (let [[sy sm] rs
                           s-idx (+ (* sy 12) sm)
                           e-idx (+ (* year 12) month)
                           [min-y min-m] (if (<= s-idx e-idx) [sy sm] [year month])
                           [max-y max-m] (if (<= s-idx e-idx) [year month] [sy sm])]
                       (reset! range-start nil)
                       (dispatch [:set-period {:start (js/Date. min-y min-m 1)
                                               :end (js/Date. max-y (inc max-m) 1)
                                               :period-type :months}])))
                   (dispatch [:set-period {:start (js/Date. year month 1)
                                           :end (js/Date. year (inc month) 1)
                                           :period-type :month}])))
      :onLongPress #(reset! range-start [year month])
      :delayLongPress 300
      :style #js {:width 38 :height 28 :borderRadius 4
                  :justifyContent "center" :alignItems "center"
                  :backgroundColor bg
                  :borderWidth (if is-range-start? 2 0)
                  :borderColor (if is-range-start? "#4a6cf7" "transparent")}}
     [:> Text {:style #js {:fontSize 11
                            :fontWeight (if (or selected? is-range-start?) "600" "400")
                            :color (cond selected? "#fff"
                                         is-range-start? "#4a6cf7"
                                         :else "#333")}}
      (get month-labels month)]]))

(defn- year-month-row [year period range-start expanded? displayed-year & [{:keys [is-collapsed-row]}]]
  (let [year-sel? (year-fully-selected? year period)]
    [:> View {:style #js {:flexDirection "row" :alignItems "center" :gap 3 :marginBottom 3}}
     [:> TouchableOpacity
      {:onPress (fn []
                  (if is-collapsed-row
                    (swap! expanded? not)
                    (do (reset! range-start nil)
                        (reset! expanded? false)
                        (reset! displayed-year year)
                        (dispatch [:set-period {:start (js/Date. year 0 1)
                                                :end (js/Date. (inc year) 0 1)
                                                :period-type :year}]))))
       :onLongPress (when is-collapsed-row
                      (fn []
                        (reset! range-start nil)
                        (reset! expanded? false)
                        (dispatch [:set-period {:start (js/Date. year 0 1)
                                                :end (js/Date. (inc year) 0 1)
                                                :period-type :year}])))
       :delayLongPress 300
       :style #js {:height 28 :borderRadius 4 :paddingHorizontal 8
                   :justifyContent "center" :alignItems "center"
                   :backgroundColor (if year-sel? "#4a6cf7" "#e0e0e0")}}
      [:> Text {:style #js {:fontSize 12 :fontWeight "700"
                             :color (if year-sel? "#fff" "#333")
                             :textAlign "center"}}
       (str year (if is-collapsed-row (if @expanded? " \u25B2" " \u25BC") "    "))]]
     (doall
      (for [month (range 12)]
        ^{:key month}
        [month-button year month period range-start expanded? displayed-year]))]))

(defn mobile-period-selector []
  (let [displayed-year (r/atom nil)
        last-period-year (r/atom nil)
        range-start (r/atom nil)
        expanded? (r/atom false)]
    (fn []
      (let [period @(subscribe [:period])
            transaction-years @(subscribe [:transaction-years])
            current-year (js/parseInt (date/current-year))
            period-year (when period (.getFullYear (:start period)))
            _ (when (and period-year (not= period-year @last-period-year))
                (reset! displayed-year period-year)
                (reset! last-period-year period-year))
            _ (when (nil? @displayed-year)
                (reset! displayed-year (or period-year current-year)))
            dy @displayed-year
            all-years (if (seq transaction-years) (sort (vec transaction-years)) [current-year])]
        [:> View {:style #js {:paddingHorizontal 8 :paddingTop 8 :paddingBottom 4}}
         (if @expanded?
           ;; Expanded: all years in a scrollable list
           (let [row-height 31
                 dy-index (.indexOf (clj->js all-years) dy)
                 scroll-y (max 0 (* (- dy-index 2) row-height))]
             [:> ScrollView {:style #js {:maxHeight 250}
                             :showsVerticalScrollIndicator true
                             :ref (fn [ref]
                                    (when ref
                                      (.scrollTo ref #js {:y scroll-y :animated false})))}
              (doall
               (for [year all-years]
                 ^{:key year}
                 [:> ScrollView {:horizontal true
                                 :showsHorizontalScrollIndicator false}
                  [year-month-row year period range-start expanded? displayed-year
                   (when (= year dy) {:is-collapsed-row true})]]))])
           ;; Collapsed: show rows for each year in the selected period
           (let [period-years (when period
                                (let [start-year (.getFullYear (:start period))
                                      end-year (.getFullYear (js/Date. (- (.getTime (:end period)) 1)))]
                                  (range start-year (inc end-year))))
                 visible-years (if (and period-years (> (count period-years) 1))
                                 (vec period-years)
                                 [dy])]
             [:> View {}
              (doall
               (for [year visible-years]
                 ^{:key year}
                 [:> ScrollView {:horizontal true
                                 :showsHorizontalScrollIndicator false}
                  [year-month-row year period range-start expanded? displayed-year
                   (when (= year dy) {:is-collapsed-row true})]]))]))]))))

;; ---------- Transactions ----------

(defn- mobile-filter-stats [stats category-map]
  (let [show-uncat? (r/atom false)
        show-cat? (r/atom false)]
    (fn [stats category-map]
      (when stats
        [:> View {:style #js {:marginTop 6}}
         [:> View {:style #js {:flexDirection "row" :gap 12}}
          [:> TouchableOpacity {:onPress #(swap! show-uncat? not)}
           [:> Text {:style #js {:fontSize 11 :color "#28a745" :textDecorationLine "underline"}}
            (str "Ukategoriserte: " (:uncategorized stats))]]
          [:> TouchableOpacity {:onPress #(swap! show-cat? not)}
           [:> Text {:style #js {:fontSize 11 :color "#007bff" :textDecorationLine "underline"}}
            (str "Kategoriserte: " (:categorized stats))]]]
         (when (and @show-uncat? (pos? (:uncategorized stats)))
           [:> View {:style #js {:marginTop 4 :backgroundColor "#fff" :borderRadius 4 :padding 6}}
            (doall
             (for [[idx txn] (map-indexed vector (:uncategorized-transactions stats))]
               ^{:key (str "us-" idx)}
               [:> View {:style #js {:flexDirection "row" :paddingVertical 2 :gap 6}}
                [:> Text {:style #js {:fontSize 10 :color "#666" :width 50 :textAlign "right"}}
                 (gstring/format "%.0f" (:amount txn))]
                [:> Text {:style #js {:fontSize 10 :color "#333" :flex 1} :numberOfLines 1}
                 (:description txn)]]))])
         (when (and @show-cat? (pos? (:categorized stats)))
           [:> View {:style #js {:marginTop 4 :backgroundColor "#fff" :borderRadius 4 :padding 6}}
            (doall
             (for [[idx txn] (map-indexed vector (:categorized-transactions stats))]
               (let [cat (get category-map (:category-id txn))]
                 ^{:key (str "cs-" idx)}
                 [:> View {:style #js {:flexDirection "row" :paddingVertical 2 :gap 6
                                       :alignItems "center"}}
                  [:> View {:style #js {:width 8 :height 8 :borderRadius 4
                                        :backgroundColor (or (:color cat) "#ccc")}}]
                  [:> Text {:style #js {:fontSize 10 :color "#666" :width 50 :textAlign "right"}}
                   (gstring/format "%.0f" (:amount txn))]
                  [:> Text {:style #js {:fontSize 10 :color "#333" :flex 1} :numberOfLines 1}
                   (:description txn)]])))])]))))

(defn- transaction-editor [idx transaction categories category-map]
  (let [scroll-ref (atom nil)
        sorted-cats (sort-by :name categories)]
    (fn [idx transaction categories category-map]
      (let [editor @(subscribe [:transaction-row-editor])
            builder-cat @(subscribe [:builder-category])
            tags @(subscribe [:tags])
            effective-cat (if (contains? editor :new-category)
                            (when (:new-category editor) (get category-map (:new-category editor)))
                            (when (:category-id transaction) (get category-map (:category-id transaction))))
            sub-filter (category/find-sub-filter effective-cat transaction)
            new-sub-filter (:new-sub-filter editor)
            filter-value (if (some? new-sub-filter) new-sub-filter (or sub-filter ""))
            filter-stats (:filter-statistics editor)
            selected-idx (when effective-cat
                           (some (fn [[i c]] (when (= (:id c) (:id effective-cat)) i))
                                 (map-indexed vector sorted-cats)))
            ;; Estimate chip width: ~8px per char + 30px padding + gap. +1 for "Ingen" chip at index 0
            scroll-to-x (when selected-idx
                          (let [chip-w 70] ;; average chip width estimate
                            (* (inc selected-idx) chip-w)))]
        [:> View {:style #js {:backgroundColor "#d0d0d0" :padding 10 :borderRadius 6 :marginTop 4 :marginBottom 4}}
         ;; Category picker
         [:> Text {:style #js {:fontSize 11 :color "#666" :marginBottom 4}} "Kategori"]
         [:> ScrollView {:horizontal true :showsHorizontalScrollIndicator false
                         :contentContainerStyle #js {:gap 6 :paddingBottom 4}
                         :ref (fn [r]
                                (when (and r (not @scroll-ref) scroll-to-x)
                                  (reset! scroll-ref r)
                                  (.scrollTo r #js {:x (max 0 (- scroll-to-x 40)) :animated false})))}
          ;; "No category" chip
          [:> TouchableOpacity
           {:onPress #(dispatch [:select-new-category ""])
            :style #js {:paddingHorizontal 10 :paddingVertical 5 :borderRadius 12
                        :backgroundColor (if (nil? effective-cat) "#4a6cf7" "#ddd")}}
           [:> Text {:style #js {:fontSize 11 :color (if (nil? effective-cat) "#fff" "#333")}} "Ingen"]]
          (doall
           (for [cat sorted-cats]
             (let [selected? (and effective-cat (= (:id cat) (:id effective-cat)))]
               ^{:key (:id cat)}
               [:> TouchableOpacity
                {:onPress #(dispatch [:select-new-category (:id cat)])
                 :style #js {:flexDirection "row" :alignItems "center" :gap 4
                             :paddingHorizontal 10 :paddingVertical 5 :borderRadius 12
                             :backgroundColor (if selected? "#4a6cf7" "#e8e8e8")}}
                [:> View {:style #js {:width 8 :height 8 :borderRadius 4
                                      :backgroundColor (or (:color cat) "#ccc")}}]
                [:> Text {:style #js {:fontSize 11 :color (if selected? "#fff" "#333")}}
                 (:name cat)]])))]
     ;; Filter input
     [:> View {:style #js {:flexDirection "row" :alignItems "center" :gap 6 :marginTop 8}}
      [:> TextInput {:value filter-value
                     :placeholder "Filter"
                     :onChangeText #(dispatch [:mark-transaction %])
                     :editable (some? effective-cat)
                     :style #js {:flex 1 :backgroundColor "#fff" :borderRadius 4
                                 :paddingHorizontal 8 :paddingVertical 4
                                 :fontSize 13 :borderWidth 1 :borderColor "#ddd"}}]
      [:> TouchableOpacity
       {:onPress #(dispatch [:mark-transaction (:description transaction)])
        :style #js {:paddingHorizontal 8 :paddingVertical 4
                    :backgroundColor "#e8e8e8" :borderRadius 4}}
       [:> Text {:style #js {:fontSize 11 :color "#333"}} "Copy"]]]
     ;; Status label
     (when effective-cat
       [:> Text {:style #js {:fontSize 10 :color "#888" :marginTop 2}}
        (if (seq filter-value) "merket av filter" "merket manuelt")])
     ;; Tags
     (when (seq tags)
       [:> View {:style #js {:flexDirection "row" :flexWrap "wrap" :gap 6 :marginTop 8}}
        (doall
         (for [tag tags]
           (let [tx-tags (set (or (:tag-ids transaction) []))
                 checked? (contains? tx-tags (:id tag))]
             ^{:key (:id tag)}
             [:> TouchableOpacity
              {:onPress #(dispatch [:toggle-transaction-tag idx (:id tag)])
               :style #js {:flexDirection "row" :alignItems "center" :gap 4
                           :paddingHorizontal 8 :paddingVertical 3
                           :backgroundColor (if checked? (or (:color tag) "#4a6cf7") "#e8e8e8")
                           :borderRadius 10}}
              [:> Text {:style #js {:fontSize 11 :color (if checked? "#fff" "#333")}}
               (:name tag)]])))])
     ;; Filter statistics
     [mobile-filter-stats filter-stats category-map]
     ;; Action buttons
     [:> View {:style #js {:flexDirection "row" :justifyContent "space-between" :marginTop 10}}
      [:> TouchableOpacity
       {:onPress #(dispatch [:update-transactions-step-one effective-cat transaction])
        :style #js {:paddingHorizontal 16 :paddingVertical 6
                    :backgroundColor "#4a6cf7" :borderRadius 4}}
       [:> Text {:style #js {:fontSize 13 :color "#fff" :fontWeight "600"}} "Lagre"]]
      [:> TouchableOpacity
       {:onPress #(dispatch [:edit-transaction-row idx])
        :style #js {:paddingHorizontal 16 :paddingVertical 6
                    :backgroundColor "#e8e8e8" :borderRadius 4}}
       [:> Text {:style #js {:fontSize 13 :color "#333"}} "Lukk"]]]]))))

(defn transaction-row [idx transaction cat-color]
  (let [editor @(subscribe [:transaction-row-editor])
        is-editing? (and (some? editor) (= (:row-index editor) idx))
        any-editing? (some? editor)
        categories @(subscribe [:categories])
        category-map (into {} (map (juxt :id identity) categories))
        amount (or (:amount transaction) 0)
        negative? (neg? amount)]
    [:> View {:style #js {:opacity (if (and any-editing? (not is-editing?)) 0.4 1)}}
     [:> TouchableOpacity {:onPress #(dispatch [:edit-transaction-row idx])}
      [:> View {:style (gobj/get styles "row")}
       [:> View {:style #js {:width 4 :borderRadius 2
                              :backgroundColor (or cat-color "#ddd")
                              :marginRight 10
                              :alignSelf "stretch"}}]
       [:> View {:style #js {:flex 1}}
        [:> Text {:style (gobj/get styles "rowDate")} (format-date (:date transaction))]
        [:> Text {:style (gobj/get styles "rowDesc") :numberOfLines 2}
         (or (:description transaction) "-")]]
       [:> Text {:style (if negative? (gobj/get styles "rowAmountNegative") (gobj/get styles "rowAmount"))}
        (str (when (pos? amount) "+") amount)]]]
     (when is-editing?
       [transaction-editor idx transaction categories category-map])]))

;; ---------- View Selector ----------

(defn mobile-view-selector []
  (let [dtd @(subscribe [:displayed-transactions-data])
        display-option (or (:display-option dtd) :table)
        btn (fn [label option]
              (let [selected? (= display-option option)]
                [:> TouchableOpacity
                 {:onPress #(dispatch [:set-display-option option])
                  :style #js {:flex 1 :height 32 :borderRadius 6
                              :justifyContent "center" :alignItems "center"
                              :backgroundColor (if selected? "#4a6cf7" "#e8e8e8")}}
                 [:> Text {:style #js {:fontSize 13
                                        :fontWeight (if selected? "600" "400")
                                        :color (if selected? "#fff" "#333")}}
                  label]]))]
    [:> View {:style #js {:flexDirection "row" :gap 6
                          :paddingHorizontal 12 :paddingVertical 8}}
     [btn "Tabell" :table]
     [btn "Diagram" :bar-chart]
     [btn "Oppsummert" :summed-table]]))

;; ---------- Mobile Summed View ----------

(defn- mobile-summed-view [transactions cat-color-map]
  (let [categories @(subscribe [:categories])
        category-map (into {} (map (juxt :id identity) categories))
        groups (summed/group-by-filter-line transactions category-map)
        sorted-groups (sort-by (fn [[_ txns]] (reduce + 0 (map :amount txns))) groups)
        expanded (r/atom #{})]
    (fn [transactions cat-color-map]
      (let [categories @(subscribe [:categories])
            category-map (into {} (map (juxt :id identity) categories))
            groups (summed/group-by-filter-line transactions category-map)
            sorted-groups (sort-by (fn [[_ txns]] (reduce + 0 (map :amount txns))) groups)
            tx-index-map (into {} (map-indexed (fn [i t] [t i]) transactions))]
        [:> View {:style #js {:paddingHorizontal 12 :paddingBottom 40}}
         (if (empty? transactions)
           [:> View {:style #js {:padding 24 :alignItems "center"}}
            [:> Text {:style #js {:color "#999"}} "Ingen transaksjoner for denne perioden."]]
           (doall
            (for [[pattern txns] sorted-groups]
              (let [total (reduce + 0 (map :amount txns))
                    cat-id (:category-id (first txns))
                    color (or (get cat-color-map cat-id) "#ddd")
                    is-expanded? (contains? @expanded pattern)]
                ^{:key (str "group-" pattern)}
                [:> View {}
                 [:> TouchableOpacity
                  {:onPress #(swap! expanded (fn [s] (if (contains? s pattern) (disj s pattern) (conj s pattern))))
                   :style #js {:flexDirection "row" :alignItems "center"
                               :backgroundColor "#f0f0f0" :borderRadius 6
                               :marginBottom 4 :paddingVertical 8 :paddingHorizontal 8}}
                  [:> View {:style #js {:width 4 :borderRadius 2 :backgroundColor color
                                        :alignSelf "stretch" :marginRight 8}}]
                  [:> Text {:style #js {:flex 1 :fontSize 13 :color "#333"} :numberOfLines 1}
                   (str (if is-expanded? "\u25BC " "\u25B6 ") pattern)]
                  [:> Text {:style #js {:fontSize 12 :color "#666" :marginRight 8}}
                   (str (count txns))]
                  [:> Text {:style #js {:fontSize 13 :fontWeight "600" :color "#333"}}
                   (gstring/format "%.0f" total)]]
                 (when is-expanded?
                   (doall
                    (for [[idx txn] (map-indexed vector txns)]
                      ^{:key (str "detail-" pattern "-" idx)}
                      [transaction-row (get tx-index-map txn) txn color])))]))))]))))

;; ---------- Mobile Chart View ----------

(defn- mobile-chart-view []
  (let [dtd @(subscribe [:displayed-transactions-data])
        displayed-txns (or (:displayed-transactions dtd) [])
        period @(subscribe [:period])
        categories @(subscribe [:categories])
        cat-color-map (into {} (map (juxt :id :color) categories))
        ;; Only show expenses (negative amounts)
        expense-txns (filter #(neg? (:amount %)) displayed-txns)
        data (when (and (seq expense-txns) period)
               (chart-svc/period-transactions->data expense-txns period))
        ;; Group by month/day label, sum per label, build stacked bars
        by-label (group-by :month data)
        sorted-labels (->> (keys by-label)
                           (sort-by chart-svc/to-iso-date))
        bar-width (max 8 (min 30 (int (/ (- screen-width 80) (max 1 (count sorted-labels))))))
        stack-data (mapv (fn [label]
                           (let [items (get by-label label)
                                 stacks (->> items
                                             (filter #(pos? (:amount %)))
                                             (mapv (fn [item]
                                                     {:value (:amount item)
                                                      :color (get cat-color-map (:category-id item) "#ccc")})))
                                 total (reduce + 0 (map :value stacks))]
                             {:stacks (if (seq stacks) stacks [{:value 0 :color "#ccc"}])
                              :label label
                              :topLabelComponent (fn []
                                                   (let [label-text (gstring/format "%.0f" total)
                                                         tw (+ 4 (* (count label-text) 5))]
                                                     (r/as-element
                                                      (when (pos? total)
                                                        [:> View {:style #js {:width tw
                                                                              :transform #js [#js {:rotate "-50deg"}
                                                                                              #js {:translateX (/ (- tw bar-width) 2)}]
                                                                              :marginBottom 10}}
                                                         [:> Text {:style #js {:fontSize 8 :color "#555"}}
                                                          label-text]]))))}))
                         sorted-labels)
        chart-el (when (seq stack-data)
                   (r/create-element
                    BarChart
                    (clj->js {:stackData stack-data
                              :barWidth bar-width
                              :spacing (max 2 (- bar-width 4))
                              :noOfSections 4
                              :xAxisLabelTextStyle {:fontSize 9 :color "#888"}
                              :yAxisTextStyle {:fontSize 10 :color "#888"}
                              :hideRules false
                              :rulesColor "#eee"
                              :width (- screen-width 60)
                              :barBorderRadius 0
                              :topLabelContainerStyle {:marginBottom 4}})))]
    [:> View {:style #js {:paddingHorizontal 4 :paddingBottom 40}}
     (if chart-el
       chart-el
       [:> Text {:style #js {:color "#999" :textAlign "center" :padding 24}}
        "Ingen data for diagram"])]))

;; ---------- Login ----------

(defn login-screen []
  (let [email (r/atom "")
        password (r/atom "")
        auth (subscribe [:auth])]
    (fn []
      [:> View {:style #js {:flex 1 :paddingTop 80 :paddingHorizontal 24 :backgroundColor "#f5f5f5"}}
       [:> Text {:style #js {:fontSize 28 :fontWeight "bold" :marginBottom 32 :color "#333" :textAlign "center"}}
        "Odin"]
       [:> TextInput {:style #js {:backgroundColor "#fff" :borderRadius 8 :padding 14
                                  :fontSize 16 :marginBottom 12 :borderWidth 1 :borderColor "#ddd"}
                      :placeholder "E-post"
                      :autoCapitalize "none"
                      :keyboardType "email-address"
                      :value @email
                      :onChangeText #(reset! email %)}]
       [:> TextInput {:style #js {:backgroundColor "#fff" :borderRadius 8 :padding 14
                                  :fontSize 16 :marginBottom 20 :borderWidth 1 :borderColor "#ddd"}
                      :placeholder "Passord"
                      :secureTextEntry true
                      :value @password
                      :onChangeText #(reset! password %)}]
       [:> TouchableOpacity {:style #js {:backgroundColor "#4a6cf7" :borderRadius 8 :padding 14 :alignItems "center"}
                             :on-press #(dispatch [:login @email @password])}
        [:> Text {:style #js {:color "#fff" :fontSize 16 :fontWeight "600"}} "Logg inn"]]
       (when (:error @auth)
         [:> Text {:style #js {:color "#c00" :marginTop 12 :textAlign "center"}}
          (:error @auth)])
       (when (:loading? @auth)
         [:> Text {:style #js {:color "#666" :marginTop 12 :textAlign "center"}}
          "Logger inn..."])])))

;; ---------- Categories Drawer ----------

(def drawer-width (* screen-width 0.82))

(def palette-colors
  (mapv (fn [h] (-> [h 0.6 0.9] color/hsv2rgb color/color-base10->base16 color/color-str))
        (color/generate-hues 16)))

(defn- color-picker [selected-color on-select]
  [:> View {:style #js {:flexDirection "row" :flexWrap "wrap" :gap 6 :marginTop 4 :marginBottom 4}}
   (doall
    (for [c palette-colors]
      ^{:key c}
      [:> TouchableOpacity
       {:onPress #(on-select c)
        :style #js {:width 28 :height 28 :borderRadius 14
                    :backgroundColor c
                    :borderWidth (if (= c selected-color) 3 0)
                    :borderColor "#333"}}]))])

(defn- filter-line-input [idx value on-change on-remove]
  [:> View {:style #js {:flexDirection "row" :alignItems "center" :gap 4 :marginBottom 4}}
   [:> TextInput {:value value
                  :onChangeText #(on-change idx %)
                  :placeholder "Filter..."
                  :style #js {:flex 1 :backgroundColor "#fff" :borderRadius 4
                              :paddingHorizontal 8 :paddingVertical 4
                              :fontSize 13 :borderWidth 1 :borderColor "#ddd"}}]
   [:> TouchableOpacity {:onPress #(on-remove idx)
                         :style #js {:padding 4}}
    [:> Text {:style #js {:fontSize 16 :color "#c00" :fontWeight "bold"}} "\u00D7"]]])

(defn- category-editor [cat on-close]
  (let [local-name (r/atom (:name cat ""))
        local-color (r/atom (:color cat "#5ce67e"))
        local-filters (r/atom (vec (or (-> cat :marker :description) [])))]
    (fn [cat on-close]
      (let [update-filter-line (fn [idx text]
                                 (swap! local-filters assoc idx text))
            remove-filter-line (fn [idx]
                                 (swap! local-filters #(vec (concat (subvec % 0 idx) (subvec % (inc idx))))))
            add-filter-line (fn [] (swap! local-filters conj ""))
            do-save (fn []
                      (dispatch [:edit-category3 (:id cat) 0])
                      (dispatch [:update-builder-category-name @local-name])
                      (dispatch [:update-builder-category-color @local-color])
                      (let [text (s/join "\n" @local-filters)]
                        (dispatch [:mark-transactions text]))
                      (js/setTimeout #(dispatch [:store-category3]) 100))
            do-delete (fn [] (dispatch [:delete-category (:id cat)]) (on-close))]
        [:> View {:style #js {:backgroundColor "#f0f0f0" :padding 10 :borderRadius 6
                               :marginTop 4 :marginBottom 8}}
         ;; Name
         [:> Text {:style #js {:fontSize 11 :color "#666" :marginBottom 2}} "Navn"]
         [:> TextInput {:value @local-name
                        :onChangeText #(reset! local-name %)
                        :style #js {:backgroundColor "#fff" :borderRadius 4
                                    :paddingHorizontal 8 :paddingVertical 4
                                    :fontSize 14 :borderWidth 1 :borderColor "#ddd" :marginBottom 6}}]
         ;; Color
         [:> Text {:style #js {:fontSize 11 :color "#666" :marginBottom 2}} "Farge"]
         [color-picker @local-color #(reset! local-color %)]
         ;; Filters
         [:> Text {:style #js {:fontSize 11 :color "#666" :marginBottom 2 :marginTop 4}} "Filtre"]
         (doall
          (for [[idx f] (map-indexed vector @local-filters)]
            ^{:key (str "f-" idx)}
            [filter-line-input idx f update-filter-line remove-filter-line]))
         [:> TouchableOpacity {:onPress add-filter-line
                               :style #js {:alignSelf "flex-start" :paddingVertical 2 :paddingHorizontal 8
                                           :backgroundColor "#e0e0e0" :borderRadius 4 :marginBottom 8}}
          [:> Text {:style #js {:fontSize 14 :color "#333"}} "+"]]
         ;; Actions
         [:> View {:style #js {:flexDirection "row" :justifyContent "space-between"}}
          [:> TouchableOpacity {:onPress do-delete
                                :style #js {:paddingHorizontal 12 :paddingVertical 6
                                            :backgroundColor "#ef4444" :borderRadius 4}}
           [:> Text {:style #js {:color "#fff" :fontSize 13 :fontWeight "600"}} "Slett"]]
          [:> TouchableOpacity {:onPress do-save
                                :style #js {:paddingHorizontal 16 :paddingVertical 6
                                            :backgroundColor "#4a6cf7" :borderRadius 4}}
           [:> Text {:style #js {:color "#fff" :fontSize 13 :fontWeight "600"}} "Lagre"]]]]))))

(defn- category-drawer-row [cat editing-id on-toggle-edit on-close-drawer]
  (let [is-editing? (= (:id cat) @editing-id)
        spent (Math/abs (or (:amount cat) 0))]
    [:> View {}
     [:> TouchableOpacity
      {:onPress #(if is-editing?
                   (reset! editing-id nil)
                   (reset! editing-id (:id cat)))
       :style #js {:flexDirection "row" :alignItems "center"
                   :paddingVertical 10 :paddingHorizontal 12
                   :borderBottomWidth 1 :borderBottomColor "#e0e0e0"}}
      [:> View {:style #js {:width 12 :height 12 :borderRadius 6
                             :backgroundColor (or (:color cat) "#ccc")
                             :marginRight 10}}]
      [:> Text {:style #js {:flex 1 :fontSize 14 :color "#333"}} (:name cat)]
      [:> Text {:style #js {:fontSize 13 :color "#666"}} (gstring/format "%.0f" spent)]]
     (when is-editing?
       [category-editor cat #(reset! editing-id nil)])]))

(defn- categories-drawer [drawer-open?]
  (let [editing-id (r/atom nil)]
    (fn [drawer-open?]
      (let [categories @(subscribe [:summed-categories])
            sorted-cats (->> categories
                             (filter :name)
                             (remove #(#{"ukategorisert-in" "ukategorisert-out"} (:id %)))
                             (sort-by :name))]
        (when @drawer-open?
          [:<>
           ;; Overlay
           [:> TouchableOpacity
            {:activeOpacity 1
             :onPress #(reset! drawer-open? false)
             :style #js {:position "absolute" :top 0 :left 0 :right 0 :bottom 0
                         :backgroundColor "rgba(0,0,0,0.4)" :zIndex 100}}]
           ;; Drawer panel
           [:> View {:style #js {:position "absolute" :top 0 :right 0 :bottom 0
                                 :width drawer-width
                                 :backgroundColor "#fff"
                                 :zIndex 101
                                 :shadowColor "#000" :shadowOpacity 0.3
                                 :shadowOffset #js {:width -3 :height 0}
                                 :shadowRadius 8
                                 :elevation 10}}
            [:> ScrollView {:style #js {:flex 1}
                            :contentContainerStyle #js {:paddingTop 60 :paddingBottom 40}}
             [:> View {:style #js {:flexDirection "row" :justifyContent "space-between"
                                   :alignItems "center" :paddingHorizontal 14 :marginBottom 12}}
              [:> Text {:style #js {:fontSize 18 :fontWeight "bold" :color "#333"}} "Kategorier"]
              [:> TouchableOpacity {:onPress #(reset! drawer-open? false)}
               [:> Text {:style #js {:fontSize 20 :color "#666"}} "\u00D7"]]]
             (doall
              (for [cat sorted-cats]
                ^{:key (:id cat)}
                [category-drawer-row cat editing-id nil #(reset! drawer-open? false)]))
             ;; New category button
             [:> TouchableOpacity
              {:onPress (fn []
                          (dispatch [:edit-category3 "new-id" 0])
                          (reset! editing-id "new-id"))
               :style #js {:margin 14 :paddingVertical 10 :backgroundColor "#4a6cf7"
                           :borderRadius 6 :alignItems "center"}}
              [:> Text {:style #js {:color "#fff" :fontSize 14 :fontWeight "600"}} "+ Ny kategori"]]]]])))))

;; ---------- Root ----------

(defn odin-mobile-app []
  (let [auth (subscribe [:auth])
        dtd (subscribe [:displayed-transactions-data])
        categories (subscribe [:categories])
        loading (subscribe [:loading])
        refreshing (subscribe [:refreshing?])
        refresh-result (subscribe [:refresh-result])
        below-height-atom (r/atom 0)
        toast-timer (atom nil)
        drawer-open? (r/atom false)]
    (fn []
      (if (:token @auth)
        (let [cat-color-map (into {} (map (juxt :id :color) @categories))
              transactions (or (:displayed-transactions @dtd) [])
              result @refresh-result]
          ;; Auto-clear refresh result after 3 seconds
          (when (and result (nil? @toast-timer))
            (reset! toast-timer
              (js/setTimeout #(do (dispatch [:clear-refresh-result])
                                  (reset! toast-timer nil))
                             3000)))
          [:> View {:style #js {:flex 1}}
           (if (= @loading "true")
            [:> View {:style #js {:flex 1 :backgroundColor "#f5f5f5" :justifyContent "center" :alignItems "center"}}
             [:> Text {:style #js {:fontSize 24 :fontWeight "bold" :color "#333"}} "Loading..."]]
            [:> ScrollView {:style #js {:flex 1 :backgroundColor "#f5f5f5"}
                            :bounces true
                            :refreshControl
                            (r/as-element
                             [:> RefreshControl {:refreshing (boolean @refreshing)
                                                 :onRefresh #(dispatch [:refresh-data])}])}
             (when result
               (let [n (:new-count result 0)
                     u (:updated-count result 0)
                     msg (cond
                           (and (zero? n) (zero? u)) "Ingen nye transaksjoner"
                           (and (pos? n) (pos? u)) (str n " nye, " u " oppdaterte")
                           (pos? n) (str n " nye transaksjoner")
                           :else (str u " oppdaterte transaksjoner"))]
                 [:> View {:style #js {:backgroundColor (if (pos? (+ n u)) "#34d399" "#888")
                                       :paddingVertical 6 :paddingHorizontal 16
                                       :alignItems "center"}}
                  [:> Text {:style #js {:color "#fff" :fontSize 13 :fontWeight "600"}} msg]]))
             [:> ScrollView {:horizontal true
                             :pagingEnabled true
                             :showsHorizontalScrollIndicator false
                             :style #js {:flexGrow 0}}
              [:> View {:style #js {:width screen-width}}
               [mobile-treemap below-height-atom :show-budget? false]
               [spending-summary]]
              [:> View {:style #js {:width screen-width}}
               [mobile-treemap below-height-atom :show-budget? true]
               [budget-summary-bars]]]
             [:> View {:onLayout (fn [^js e]
                                   (let [h (-> e .-nativeEvent .-layout .-height)]
                                     (when (and (pos? h) (> h @below-height-atom))
                                       (reset! below-height-atom h))))}
              [mobile-period-selector]
              [mobile-view-selector]]
             (let [display-option (or (-> @dtd :display-option) :table)]
               (case display-option
                 :table [:> View {:style #js {:paddingHorizontal 16 :paddingBottom 40}}
                         (if (empty? transactions)
                           [:> View {:style #js {:padding 24 :alignItems "center"}}
                            [:> Text {:style #js {:color "#999"}} "Ingen transaksjoner for denne perioden."]]
                           (doall
                            (map-indexed
                             (fn [idx t]
                               ^{:key idx}
                               [transaction-row idx t (get cat-color-map (:category-id t))])
                             transactions)))]
                 :bar-chart [mobile-chart-view]
                 :summed-table [mobile-summed-view transactions cat-color-map]
                 [:> View {:style #js {:padding 24 :alignItems "center"}}
                  [:> Text {:style #js {:color "#999"}} "Ukjent visning"]]))])
           ;; Drawer toggle button
           [:> TouchableOpacity
            {:onPress #(reset! drawer-open? true)
             :style #js {:position "absolute" :top 50 :right 14 :zIndex 50
                         :width 36 :height 36 :borderRadius 18
                         :backgroundColor "rgba(0,0,0,0.5)"
                         :justifyContent "center" :alignItems "center"}}
            [:> Text {:style #js {:color "#fff" :fontSize 18}} "\u2699"]]
           ;; Categories drawer
           [categories-drawer drawer-open?]])
        [login-screen]))))
