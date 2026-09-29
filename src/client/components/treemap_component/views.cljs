(ns client.components.treemap-component.views
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [goog.string :as gstring]
            [goog.string.format]
            [common.category-service :as category]
            [client.components.treemap-component.layout :as layout]
            [client.services.format-service :as fmt]))

(def ^:private parse-target layout/parse-target)
(def ^:private darken-color layout/darken-color)
(def ^:private lighten-color layout/lighten-color)

;; Layout algorithm is in client.components.treemap-component.layout

;; ---------- Component ----------

(defn- compute-sub-filter-amounts
  "Given a full category (with :marker) and its transactions, return sub-items
   for each filter line plus an 'Annet' bucket for unmatched transactions.
   When selected-tag is provided, computes :tag-ratio and :tag-color for each sub-item."
  [full-category category-transactions base-color selected-tag]
  (let [lines (-> full-category :marker :description)
        filters (or (:filters full-category) [])
        filter-text->obj (into {} (map (juxt :text identity) filters))
        tag-id (when selected-tag (:id selected-tag))
        tag-color (when selected-tag (:color selected-tag))]
    (when (seq lines)
      (let [grouped (group-by
                     (fn [txn]
                       (some #(when (category/match-fun (:description txn) %) %) lines))
                     category-transactions)
            sub-items (into []
                        (comp
                         (map-indexed
                          (fn [idx line]
                            (let [txns (get grouped line [])]
                              (when (seq txns)
                                (let [total-amt (reduce + 0 (map #(Math/abs (:amount %)) txns))
                                      filter-obj (get filter-text->obj line)
                                      filter-has-tag? (and tag-id filter-obj
                                                           (some #{tag-id} (:tag-ids filter-obj)))
                                      tr (when tag-id
                                           (if filter-has-tag?
                                             1.0
                                             (let [tagged-amt (reduce + 0
                                                                (map #(Math/abs (:amount %))
                                                                     (filter #(some #{tag-id} (:tag-ids %)) txns)))]
                                               (when (pos? total-amt) (/ tagged-amt total-amt)))))]
                                  (cond-> {:id    (str (:id full-category) "-" idx)
                                           :name  line
                                           :color base-color
                                           :value total-amt}
                                    (and tr (pos? tr)) (assoc :tag-ratio tr :tag-color tag-color)))))))
                         (filter some?))
                        lines)
            unmatched (get grouped nil [])
            annet (when (seq unmatched)
                    (let [total-amt (reduce + 0 (map #(Math/abs (:amount %)) unmatched))
                          tr (when tag-id
                               (let [tagged-amt (reduce + 0
                                                  (map #(Math/abs (:amount %))
                                                       (filter #(some #{tag-id} (:tag-ids %)) unmatched)))]
                                 (when (pos? total-amt) (/ tagged-amt total-amt))))]
                      (cond-> {:id    (str (:id full-category) "-annet")
                               :name  "Annet"
                               :color base-color
                               :value total-amt}
                        (and tr (pos? tr)) (assoc :tag-ratio tr :tag-color tag-color))))]
        (cond-> sub-items
          annet (conj annet))))))

(defn- label-styles [min-dim]
  {:name {:color "#fff"
          :font-size (cond (> min-dim 80) "14px"
                           (> min-dim 50) "12px"
                           :else "10px")
          :font-weight "600"
          :text-shadow "0 1px 3px rgba(0,0,0,0.5)"
          :line-height "1.3"
          :text-align "center"
          :padding "0 4px"
          :max-width "100%"
          :overflow "hidden"
          :text-overflow "ellipsis"
          :white-space "nowrap"}
   :pct  {:color "rgba(255,255,255,0.9)"
          :font-size (if (> min-dim 80) "12px" "10px")
          :text-shadow "0 1px 3px rgba(0,0,0,0.5)"
          :text-align "center"}})

(defn- treemap-rect [{:keys [id name color value target tag-ratio tag-color]} rect pct-base
                     hovered-id selected-name show-targets?]
  (let [{ix :x iy :y iw :w ih :h} rect
        pct     (when (and pct-base (pos? pct-base))
                  (* 100 (/ value pct-base)))
        min-dim (min iw ih)
        hv      @hovered-id
        is-hovered? (= hv id)
        is-selected? (= selected-name name)
        highlight-id (or selected-name hv)
        is-highlighted? (if selected-name is-selected? is-hovered?)
        opacity (if (some? highlight-id) (if is-highlighted? 1 0.4) 1)
        base-color (or color "#9ca3af")
        cat-target (parse-target target)
        show-split? (and show-targets? cat-target (not= value cat-target))
        rect-value (max value (or cat-target 0))
        normal-ratio (if (and show-split? (pos? rect-value))
                       (/ (min value cat-target) rect-value)
                       1.0)
        horizontal? (>= iw ih)
        over-target? (and show-split? (> value cat-target))
        under-color (lighten-color base-color 0.5)
        styles (label-styles min-dim)]
    ^{:key id}
    [:div {:style {:position         "absolute"
                   :left             (str (+ ix 1) "px")
                   :top              (str (+ iy 1) "px"):width            (str (max 0 (- iw 2)) "px")
                   :height           (str (max 0 (- ih 2)) "px")
                   :box-sizing       "border-box"
                   :overflow         "hidden"
                   :border-radius    "8px"
                   :cursor           "pointer"
                   :transition       "opacity 0.15s ease"
                   :opacity          opacity}
           :on-mouse-enter #(reset! hovered-id id)
           :on-mouse-leave #(reset! hovered-id nil)
           :on-click #(dispatch [:view-category name])
           :title (str name " — " (when pct (gstring/format "%.1f%%" pct))
                       " (" (fmt/format-amount value) ")"
                       (when cat-target (str " target: " (fmt/format-amount cat-target))))}
     (if show-split?
       (let [normal-pct (str (* 100 normal-ratio) "%")
             accent-pct (str (* 100 (- 1 normal-ratio)) "%")]
         [:<>
          [:div {:style {:display "flex"
                         :flex-direction (if horizontal? "row" "column")
                         :width "100%" :height "100%"}}
           [:div {:style (merge {:background-color base-color}
                                (if horizontal?
                                  {:width normal-pct :height "100%"}
                                  {:height normal-pct :width "100%"}))}]
           [:div {:style (merge {:background-color (if over-target? base-color under-color)}
                                (if horizontal?
                                  {:width accent-pct :height "100%"}
                                  {:height accent-pct :width "100%"}))}]]
          (when over-target?
            (let [stripe-color (darken-color base-color 0.6)]
              [:div {:style (merge {:position "absolute" :pointer-events "none"
                                    :box-sizing "border-box"
                                    :background (str "repeating-linear-gradient(45deg, transparent, transparent 4px, " stripe-color " 4px, " stripe-color " 6px)")}
                                 (if horizontal?
                                   {:top "0" :right "0" :width accent-pct :height "100%"}
                                   {:bottom "0" :left "0" :height accent-pct :width "100%"}))}]))
          (when (and tag-ratio (pos? tag-ratio))
            [:div {:style {:position "absolute" :pointer-events "none"
                           :top "0" :left "0"
                           :width (if (>= tag-ratio 1.0) "100%" (str (* 100 tag-ratio) "%"))
                           :height "100%"
                           :background (str "repeating-linear-gradient(45deg, transparent, transparent 20px, "
                                            tag-color " 20px, " tag-color " 30px)")}}])
          [:div {:style {:position "absolute" :inset "0"
                         :display "flex" :flex-direction "column"
                         :align-items "center" :justify-content "center"
                         :pointer-events "none"}}
           (when (> min-dim 20)
             [:span {:style (:name styles)} name])
           (when (and pct (> min-dim 40))
             [:span {:style (:pct styles)}
              (gstring/format "%.1f%%" pct)])]])
       [:<>
        [:div {:style {:background-color base-color
                       :width "100%" :height "100%"}}]
        (when (and tag-ratio (pos? tag-ratio))
          [:div {:style {:position "absolute" :pointer-events "none"
                         :top "0" :left "0"
                         :width (if (>= tag-ratio 1.0) "100%" (str (* 100 tag-ratio) "%"))
                         :height "100%"
                         :background (str "repeating-linear-gradient(45deg, transparent, transparent 20px, "
                                          tag-color " 20px, " tag-color " 30px)")}}])
        [:div {:style {:position "absolute" :inset "0"
                       :display "flex" :flex-direction "column"
                       :align-items "center" :justify-content "center"
                       :pointer-events "none"
                       :overflow "hidden"}}
         (when (> min-dim 20)
           [:span {:style (:name styles)} name])
         (when (and pct (> min-dim 40))
           [:span {:style (:pct styles)}
            (gstring/format "%.1f%%" pct)])]])]))

(defn- treemap-sub-rect [{:keys [id name color value tag-ratio tag-color]} rect pct-base hovered-id parent-name filter-path]
  (let [{ix :x iy :y iw :w ih :h} rect
        pct     (when (and pct-base (pos? pct-base))
                  (* 100 (/ value pct-base)))
        min-dim (min iw ih)
        hv      @hovered-id
        is-hovered? (= hv id)
        is-annet? (= name "Annet")
        ;; Determine if this sub-rect is selected/faded
        selected-cat (when (>= (count filter-path) 1) (first filter-path))
        selected-filter (when (= (count filter-path) 2) (second filter-path))
        is-in-selected-cat? (= parent-name selected-cat)
        is-selected? (and is-in-selected-cat? (= name selected-filter))
        ;; Fade logic: if any selection exists, fade non-selected items
        has-selection? (seq filter-path)
        opacity (cond
                  (not has-selection?) (if (and (some? hv) (not is-hovered?)) 0.6 1)
                  is-selected? 1
                  (and is-in-selected-cat? (nil? selected-filter)) 1
                  :else 0.4)
        styles (label-styles min-dim)]
    ^{:key id}
    [:div {:style {:position "absolute"
                   :left (str ix "px") :top (str iy "px")
                   :width (str iw "px") :height (str ih "px")
                   :box-sizing "border-box"
                   :overflow "hidden"
                   :cursor (if is-annet? "default" "pointer")
                   :transition "opacity 0.15s ease"
                   :opacity opacity
                   :border "1px solid rgba(255,255,255,0.4)"}
           :on-mouse-enter #(reset! hovered-id id)
           :on-mouse-leave #(reset! hovered-id nil)
           :on-click (when-not is-annet?
                       #(if is-selected?
                          (dispatch [:navigate [nil nil []]])
                          (dispatch [:navigate [nil nil [parent-name name]]])))
           :title (str name " — " (fmt/format-amount value)
                       (when pct (str " (" (gstring/format "%.1f%%" pct) ")")))}
     [:<>
      [:div {:style {:background-color color
                     :width "100%" :height "100%"}}]
      (when (and tag-ratio (pos? tag-ratio))
        [:div {:style {:position "absolute" :pointer-events "none"
                       :top "0" :left "0"
                       :width (if (>= tag-ratio 1.0) "100%" (str (* 100 tag-ratio) "%"))
                       :height "100%"
                       :background (str "repeating-linear-gradient(45deg, transparent, transparent 20px, "
                                        tag-color " 20px, " tag-color " 30px)")}}])
      [:div {:style {:position "absolute" :inset "0"
                     :display "flex" :flex-direction "column"
                     :align-items "center" :justify-content "center"
                     :pointer-events "none"
                     :overflow "hidden"}}
       (when (> min-dim 20)
         [:span {:style (merge (:name styles) {:font-size (cond (> min-dim 80) "11px"
                                                                (> min-dim 50) "9px"
                                                                :else "8px")})} name])
       (when (and pct (> min-dim 40))
         [:span {:style (:pct styles)}
          (gstring/format "%.1f%%" pct)])]]]))

(defn- treemap-expanded-rect [cat-item rect sub-items pct-base hovered-id filter-path]
  (let [{ix :x iy :y iw :w ih :h} rect
        min-dim (min iw ih)
        cat-name (:name cat-item)
        total-value (reduce + 0 (map :value sub-items))
        sub-pct-base total-value
        body-area (* iw ih)
        ;; Fade entire category if a different category is selected
        selected-cat (when (>= (count filter-path) 1) (first filter-path))
        is-selected-cat? (or (nil? selected-cat) (= cat-name selected-cat))
        opacity (if is-selected-cat? 1 0.4)]
    (if (< min-dim 40)
      [treemap-rect cat-item rect pct-base hovered-id selected-cat false]
      ^{:key (:id cat-item)}
      [:div {:style {:position "absolute"
                     :left (str ix "px") :top (str iy "px")
                     :width (str iw "px") :height (str ih "px")
                     :box-sizing "border-box"
                     :overflow "hidden"
                     :transition "opacity 0.15s ease"
                     :opacity opacity}}
       (when (and (pos? ih) (pos? body-area) (seq sub-items))
         (let [items-with-area (mapv (fn [si]
                                       (assoc si :area (* body-area (/ (:value si) total-value))))
                                     sub-items)
               laid-out (layout/squarify items-with-area {:x 0 :y 0 :w iw :h ih})]
           [:div {:style {:position "relative" :width (str iw "px") :height (str ih "px")}}
            (doall
             (for [item laid-out]
               [treemap-sub-rect item (:rect item) sub-pct-base hovered-id cat-name filter-path]))]))])))

(def hovered-category-id (r/atom nil))

(defn treemap
  "Treemap visualization of category spending.

   Props:
     :categories    – [{:id :name :color :amount :target}]
     :pct-base      – denominator for percentage labels
     :show-targets? – whether to show target sub-rectangles
     :height-ratio  – height = width × ratio (default 0.5)"
  []
  (let [width-atom    (r/atom nil)
        ref-atom      (r/atom nil)
        obs-atom      (r/atom nil)
        hovered-id    hovered-category-id]
    (r/create-class
     {:component-did-mount
      (fn [_]
        (when-let [el @ref-atom]
          (let [obs (js/ResizeObserver.
                     (fn [entries]
                       (when-let [e (aget entries 0)]
                         (reset! width-atom (.-width (.-contentRect e))))))]
            (.observe obs el)
            (reset! obs-atom obs))))

      :component-will-unmount
      (fn [_]
        (when-let [obs @obs-atom]
          (.disconnect obs)))

      :reagent-render
      (fn [{:keys [categories pct-base show-targets? show-filters?
                   full-categories period-transactions selected-tag height-ratio]
            :or   {height-ratio 0.5 show-targets? false show-filters? false}}]
        (let [cw @width-atom
              ch (when cw (* cw height-ratio))
              filter-path @(subscribe [:filter-path])
              selected-name (when (>= (count filter-path) 1) (first filter-path))

              cats (->> categories
                        (remove #(layout/excluded-ids (:id %)))
                        (filter #(or (neg? (:amount %))
                                     (and show-targets? (parse-target (:target %)))))
                        (map (fn [c]
                               (let [amt (Math/abs (:amount c))
                                     tgt (parse-target (:target c))]
                                 (assoc c
                                        :value amt
                                        :target tgt))))
                        (filter #(pos? (if show-targets?
                                         (max (:value %) (or (:target %) 0))
                                         (:value %))))
                        (sort-by #(if show-targets?
                                    (max (:value %) (or (:target %) 0))
                                    (:value %))
                                 >))

              ;; Lookups needed for show-filters? or selected-tag
              need-txn-lookups? (or show-filters? selected-tag)
              cat-name->full (when need-txn-lookups?
                               (into {} (map (juxt :name identity) full-categories)))
              cat-id->txns   (when need-txn-lookups?
                               (group-by :category-id period-transactions))

              total-value (reduce + 0
                                  (map #(if show-targets?
                                          (max (:value %) (or (:target %) 0))
                                          (:value %))
                                       cats))
              vw (when (and cw (pos? total-value)) cw)
              va (when (and vw ch) (* vw ch))

              tag-id (when selected-tag (:id selected-tag))
              tag-color (when selected-tag (:color selected-tag))

              items    (when va
                         (mapv (fn [c]
                                 (let [effective (if show-targets?
                                                   (max (:value c) (or (:target c) 0))
                                                   (:value c))
                                       cat-txns (when need-txn-lookups?
                                                  (get cat-id->txns (:id c) []))
                                       sub-items (when show-filters?
                                                   (when-let [full-cat (get cat-name->full (:name c))]
                                                     (compute-sub-filter-amounts
                                                      full-cat
                                                      cat-txns
                                                      (or (:color c) "#9ca3af")
                                                      selected-tag)))
                                       ;; Tag ratio for category rect (when filters OFF)
                                       tr (when (and tag-id (not show-filters?) (pos? (:value c)))
                                            (let [tagged-amt (reduce + 0
                                                               (map #(Math/abs (:amount %))
                                                                    (filter #(some #{tag-id} (:tag-ids %)) cat-txns)))]
                                              (when (pos? tagged-amt) (/ tagged-amt (:value c)))))]
                                   (cond-> (assoc c :area (* va (/ effective total-value)))
                                     (seq sub-items) (assoc :sub-items sub-items)
                                     (and tr (pos? tr)) (assoc :tag-ratio tr :tag-color tag-color))))
                               cats))
              laid-out (when (and items (seq items) (pos? vw) (pos? ch))
                         (layout/squarify items {:x 0 :y 0 :w vw :h ch}))]

          [:div {:ref   #(when % (reset! ref-atom %))
                 :style {:width            "100%"
                         :aspect-ratio     (/ 1 height-ratio)
                         :height           (when ch (str ch "px"))
                         :position         "relative"
                         :overflow         "hidden"
                         :box-sizing       "border-box"}}

           (when laid-out
             (doall
              (for [item laid-out]
                (if (and show-filters? (seq (:sub-items item)))
                  [treemap-expanded-rect item (:rect item) (:sub-items item) pct-base hovered-id filter-path]
                  [treemap-rect item (:rect item) pct-base
                   hovered-id selected-name show-targets?]))))]))})))

(defn- treemap-arrow-above [pct label]
  [:div {:style {:position "absolute"
                 :left (str pct "%")
                 :bottom "0"
                 :transform "translateX(-50%)"
                 :display "flex" :flex-direction "column"
                 :align-items "center"
                 :white-space "nowrap"}}
   [:span {:style {:font-size "13px" :color "#555" :margin-bottom "2px"}}
    label]
   [:svg {:width 14 :height 10 :viewBox "0 0 14 10"
          :style {:display "block"}}
    [:polygon {:points "7,10 0,0 14,0" :fill "#6b7280"}]]])

(defn- treemap-arrow-below [pct label]
  [:div {:style {:position "absolute"
                 :left (str pct "%")
                 :top "0"
                 :transform "translateX(-50%)"
                 :display "flex" :flex-direction "column"
                 :align-items "center"
                 :white-space "nowrap"}}
   [:svg {:width 14 :height 10 :viewBox "0 0 14 10"
          :style {:display "block" :transform "rotate(180deg)"}}
    [:polygon {:points "7,10 0,0 14,0" :fill "#6b7280"}]]
   [:span {:style {:font-size "13px" :color "#555" :margin-top "2px"}}
    label]])

(defn- budget-summary-bar
  "Mobile-style segmented bar showing budget breakdown (Brukt / Overforbruk /
   Uncategorized / Gjenstår) plus an arithmetic expression for current month's
   available balance vs remaining budget.

   Segments and their amounts are passed in directly so the caller controls
   classification."
  [{:keys [spent overuse uncategorized remaining balance current-month?]}]
  (let [total (+ spent overuse uncategorized remaining)]
    (when (pos? total)
      (let [pct       (fn [v] (* 100 (/ v total)))
            segments  (cond-> [{:label "Brukt"      :amount spent
                                :color "#4a6cf7"    :text-color "#333"
                                :pct (pct spent)}]
                        (pos? overuse)
                        (conj {:label "Overforbruk" :amount overuse
                               :color "#ef4444"     :text-color "#c00"
                               :pct (pct overuse)})

                        (pos? uncategorized)
                        (conj {:label nil           :amount uncategorized
                               :color "#9ca3af"     :text-color "#666"
                               :pct (pct uncategorized)})

                        (pos? remaining)
                        (conj {:label "Gjenstår"    :amount remaining
                               :color "#34d399"     :text-color "#333"
                               :pct (pct remaining)}))
            ;; Estimated label width as a fraction of the bar width (~150px / ~850px).
            ;; Used to detect when a right-anchored label would overlap the previous one
            ;; and should be moved to a row below the bar instead.
            text-est-pct 18
            placements
            (loop [i 0
                   cum 0
                   prev-text-right 0
                   result []]
              (if (>= i (count segments))
                result
                (let [seg (nth segments i)
                      has-label? (some? (:label seg))
                      bar-pct (:pct seg)
                      bar-right (+ cum bar-pct)
                      right-pct (- 100 bar-right)
                      text-left (if has-label?
                                  (max 0 (- bar-right text-est-pct))
                                  bar-right)
                      overlaps-prev? (and has-label? (pos? i) (< text-left prev-text-right))
                      text-below? overlaps-prev?
                      my-text-right (cond
                                      (not has-label?) prev-text-right
                                      text-below?      prev-text-right
                                      :else            (min 100 (max bar-right text-est-pct)))]
                  (recur (inc i)
                         bar-right
                         my-text-right
                         (conj result {:left-pct cum
                                       :bar-pct bar-pct
                                       :right-pct right-pct
                                       :text-below? text-below?})))))
            below-placements
            (loop [i 0 rows [] result []]
              (if (>= i (count segments))
                result
                (let [seg (nth segments i)
                      p (nth placements i)
                      has-label? (some? (:label seg))]
                  (if (or (not (:text-below? p)) (not has-label?))
                    (recur (inc i) rows (conj result nil))
                    (let [bar-right (+ (:left-pct p) (:bar-pct p))
                          text-left (max 0 (- bar-right text-est-pct))
                          row-idx (loop [r 0]
                                    (if (>= r (count rows))
                                      r
                                      (if (< text-left (nth rows r))
                                        (recur (inc r))
                                        r)))
                          new-right (min 100 (max bar-right text-est-pct))
                          rows (if (>= row-idx (count rows))
                                 (conj rows new-right)
                                 (assoc rows row-idx new-right))]
                      (recur (inc i) rows (conj result row-idx)))))))
            below-row-count (count (distinct (filter some? below-placements)))
            render-label (fn [seg]
                           [:span
                            [:span {:style {:color "#888" :margin-right "4px"}} (:label seg)]
                            [:span {:style {:font-weight "600" :color (:text-color seg)}}
                             (fmt/format-amount (:amount seg))]])]
        [:div {:style {:margin-top "12px"}}
         ;; Above-bar labels: one row, right-anchored to each segment that fits there
         [:div {:style {:position "relative" :height "18px" :margin-bottom "2px"}}
          (doall
           (for [[i seg] (map-indexed vector segments)
                 :let [p (nth placements i)]
                 :when (and (:label seg) (not (:text-below? p)))]
             ^{:key (str "above-" i)}
             [:div {:style {:position "absolute"
                            :right (str (:right-pct p) "%")
                            :bottom "0"
                            :font-size "12px"
                            :white-space "nowrap"
                            :padding-right "4px"}}
              (render-label seg)]))]
         ;; Continuous segmented bar
         [:div {:style {:display "flex" :height "10px" :border-radius "5px" :overflow "hidden"}}
          (doall
           (for [[i seg] (map-indexed vector segments)]
             ^{:key (str "bar-" i)}
             [:div {:style {:flex (:pct seg) :background-color (:color seg)}}]))]
         ;; Below-bar label rows (one per row that was needed)
         (when (pos? below-row-count)
           (doall
            (for [row (range below-row-count)]
              ^{:key (str "below-row-" row)}
              [:div {:style {:position "relative" :height "18px"
                             :margin-top (if (zero? row) "4px" "0")}}
               (doall
                (for [[i seg] (map-indexed vector segments)
                      :let [p (nth placements i)
                            bp (nth below-placements i)]
                      :when (and (:label seg) (:text-below? p) (= bp row))]
                  ^{:key (str "below-" i)}
                  [:div {:style {:position "absolute"
                                 :right (str (:right-pct p) "%")
                                 :top "0"
                                 :font-size "12px"
                                 :white-space "nowrap"
                                 :padding-right "4px"}}
                   (render-label seg)]))])))
         ;; Arithmetic expression for current month: balance − remaining = leftover
         (when current-month?
           (let [diff (when balance (- balance remaining))]
             [:div {:style {:margin-top "10px" :padding-top "8px"
                            :border-top "1px solid #e5e7eb"
                            :text-align "center" :font-size "14px"
                            :color (if balance "#333" "#999")}}
              (if balance
                [:span
                 [:span (fmt/format-amount balance)]
                 [:span {:style {:margin "0 6px" :color "#888"}} "−"]
                 [:span (fmt/format-amount remaining)]
                 [:span {:style {:margin "0 6px" :color "#888"}} "="]
                 [:span {:style {:font-weight "600"
                                 :color (cond (nil? diff) "#999"
                                              (neg? diff)  "#ef4444"
                                              :else         "#34d399")}}
                  (fmt/format-amount diff)]]
                (str "— − " (fmt/format-amount remaining) " = —"))]))]))))

(defn- diff-bar
  "Horizontal bar spanning between target-pct and spending-pct.
   Red when over budget, green when under."
  [target-pct spending-pct diff-value]
  (let [left-pct  (min target-pct spending-pct)
        right-pct (max target-pct spending-pct)
        over?     (> spending-pct target-pct)
        bar-color (if over? "#ef4444" "#22c55e")]
    [:div {:style {:position "relative" :height "22px" :margin-top "2px"}}
     [:div {:style {:position "absolute"
                    :left (str left-pct "%")
                    :width (str (- right-pct left-pct) "%")
                    :top "0"
                    :height "4px"
                    :background-color bar-color
                    :border-radius "2px"}}]
     [:span {:style {:position "absolute"
                     :left (str left-pct "%")
                     :top "6px"
                     :font-size "12px"
                     :font-weight "600"
                     :color bar-color
                     :white-space "nowrap"}}
      (str (if over? "+" "-") (fmt/format-amount (Math/abs diff-value)))]]))

(defn category-treemap
  "Subscribes to :summed-categories. Renders a treemap at full width.
   `Vis budsjett` / `Vis filtre` are now controlled by re-frame state
   (:treemap-show-targets? / :treemap-show-filters?), driven from the metrics-bar.

   Props:
     :height-ratio – optional (default 0.5)"
  []
  (fn [{:keys [height-ratio] :or {height-ratio 0.5}}]
    (let [categories @(subscribe [:summed-categories])
          full-categories @(subscribe [:categories])
          period-txns @(subscribe [:period-transactions])
          period     @(subscribe [:period])
          filter-path @(subscribe [:filter-path])
          selected-tag @(subscribe [:selected-tag])
          balance-data @(subscribe [:balance])
          balance (:available-balance balance-data)
          show-targets? @(subscribe [:treemap-show-targets?])
          show-filters? @(subscribe [:treemap-show-filters?])
          single-month? (= :month (:period-type period))
          targets?   (and single-month? show-targets?)
          filters?   show-filters?
            visible    (->> categories
                            (remove #(layout/excluded-ids (:id %)))
                            (filter #(or (neg? (:amount %))
                                         (and targets? (parse-target (:target %)))))
                            (filter #(or (pos? (Math/abs (:amount %)))
                                         (parse-target (:target %)))))
            total-spending (reduce + 0 (map #(Math/abs (:amount %)) visible))
            target-sum     (reduce + 0 (map #(or (parse-target (:target %)) 0) visible))
            total-area     (reduce + 0 (map #(max (Math/abs (:amount %))
                                                  (or (parse-target (:target %)) 0))
                                            visible))
            pct-base       (if targets? target-sum total-spending)
            target-pct     (when (and targets? (pos? total-area) (pos? target-sum))
                             (* 100 (/ target-sum total-area)))
            spending-pct   (if targets?
                             (when (pos? total-area) (* 100 (/ total-spending total-area)))
                             100)
            diff-value     (- total-spending target-sum)

            ;; Compute filter-path bar data
            fp-cat-name    (when (>= (count filter-path) 1) (first filter-path))
            fp-filter-name (when (= (count filter-path) 2) (second filter-path))
            fp-cat         (when fp-cat-name
                             (some #(when (= (:name %) fp-cat-name) %) visible))
            fp-color       (when fp-cat (or (:color fp-cat) "#9ca3af"))
            fp-value       (when fp-cat-name
                             (if fp-filter-name
                               ;; Sub-filter selected: sum matching transactions
                               (let [full-cat (some #(when (= (:name %) fp-cat-name) %) full-categories)
                                     cat-txns (filter #(= (:category-id %) (:id fp-cat)) period-txns)]
                                 (when full-cat
                                   (reduce + 0
                                           (map #(Math/abs (:amount %))
                                                (filter #(category/match-fun (:description %) fp-filter-name) cat-txns)))))
                               ;; Category selected: use category amount
                               (when fp-cat (Math/abs (:amount fp-cat)))))

            ;; Compute selected-tag bar data
            tag-value      (when selected-tag
                             (let [tag-id (:id selected-tag)]
                               (reduce + 0
                                       (map #(Math/abs (:amount %))
                                            (filter #(some #{tag-id} (:tag-ids %)) period-txns)))))
            tag-bar-color  (when selected-tag (:color selected-tag))]
        [:div.treemap-wrap
         (when (or targets? filters? selected-tag)
           [:div {:style {:display "flex" :flex-wrap "wrap" :align-items "center" :gap "12px"
                          :margin-bottom "4px" :font-size "11px" :color "#888"}}
            (when targets?
              [:<>
               [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
                [:div {:style {:width "12px" :height "12px" :background-color "#9ca3af"
                               :border-radius "2px"}}]
                [:div {:style {:width "12px" :height "12px" :border-radius "2px"
                               :background-color (lighten-color "#9ca3af" 0.5)}}]
                "Budsjett"]
               [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
                [:div {:style {:width "12px" :height "12px" :border-radius "2px"
                               :background "repeating-linear-gradient(45deg, transparent, transparent 2px, #6b7280 2px, #6b7280 3px)"}}]
                "Over budsjett"]])
            (when filters?
              [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
               [:div {:style {:width "12px" :height "12px" :border-radius "2px"
                               :border "1px solid #ccc"
                               :background "linear-gradient(135deg, #9ca3af 50%, #b0b8c4 50%)"}}]
               "Underkategorier"])
            (when selected-tag
              [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
               [:div {:style {:width "12px" :height "12px" :border-radius "2px"
                               :background (str "repeating-linear-gradient(45deg, transparent, transparent 4px, "
                                                (:color selected-tag) " 4px, " (:color selected-tag) " 6px)")}}]
               (:name selected-tag)])])
         (when targets?
           [:div {:style {:position "relative" :height "28px" :margin-bottom "2px"}}
            (when target-pct
              [treemap-arrow-above target-pct (fmt/format-amount target-sum)])])
         [treemap {:categories           categories
                   :pct-base             pct-base
                   :show-targets?        targets?
                   :show-filters?        filters?
                   :full-categories      full-categories
                   :period-transactions  period-txns
                   :selected-tag         selected-tag
                   :height-ratio         height-ratio}]
         [:div {:style {:position "relative" :height "28px" :margin-top "2px"}}
          (when (and spending-pct (pos? total-spending))
            [treemap-arrow-below spending-pct (fmt/format-amount total-spending)])]
         (when (and tag-value (pos? tag-value) (pos? total-spending))
           (let [bar-pct (min 100 (* 100 (/ tag-value total-spending)))]
             [:div {:style {:position "relative" :height "22px" :margin-top "2px"}}
              [:div {:style {:position "absolute"
                             :left "0"
                             :width (str bar-pct "%")
                             :top "0"
                             :height "4px"
                             :border-radius "2px"
                             :background-color tag-bar-color}}]
              [:span {:style {:position "absolute"
                              :left "0"
                              :top "6px"
                              :font-size "12px"
                              :font-weight "600"
                              :color tag-bar-color
                              :white-space "nowrap"}}
               (fmt/format-amount tag-value)]]))
         (when (and targets? target-pct spending-pct)
           [diff-bar target-pct spending-pct diff-value])
         (when (and targets? (pos? target-sum))
           (let [now (js/Date.)
                 current-month? (and single-month?
                                     (= (.getFullYear (:start period)) (.getFullYear now))
                                     (= (.getMonth (:start period)) (.getMonth now)))
                 budgeted (filter #(pos? (or (parse-target (:target %)) 0)) visible)
                 unbudgeted (remove #(pos? (or (parse-target (:target %)) 0)) visible)
                 spent (reduce + 0 (map (fn [c]
                                          (min (Math/abs (:amount c))
                                               (or (parse-target (:target c)) 0)))
                                        budgeted))
                 overuse (reduce + 0 (map (fn [c]
                                            (max 0 (- (Math/abs (:amount c))
                                                      (or (parse-target (:target c)) 0))))
                                          budgeted))
                 uncategorized (reduce + 0 (map #(Math/abs (:amount %)) unbudgeted))
                 remaining (max 0 (- target-sum spent))]
             [budget-summary-bar {:spent spent
                                  :overuse overuse
                                  :uncategorized uncategorized
                                  :remaining remaining
                                  :balance balance
                                  :current-month? current-month?}]))
         [:div.treemap-foot
          [:span.spent
           (str "▲ " (fmt/format-kr total-spending) " brukt")]
          (when (and targets? (pos? target-sum))
            [:span.budget
             (str " / " (fmt/format-kr target-sum) " budsjett")])]])))

(defn- unallocated-label-styles [min-dim]
  {:name {:color "var(--text-dim)"
          :font-size (cond (> min-dim 80) "14px"
                           (> min-dim 50) "12px"
                           :else "10px")
          :font-weight "600"
          :line-height "1.3"
          :text-align "center"
          :padding "0 4px"
          :max-width "100%"
          :overflow "hidden"
          :text-overflow "ellipsis"
          :white-space "nowrap"}
   :pct  {:color "var(--text-faint)"
          :font-size (if (> min-dim 80) "12px" "10px")
          :text-align "center"}})

(defn- target-treemap-rect
  [{:keys [id name color value unallocated?]} rect pct-base hovered-id selected-id]
  (let [{ix :x iy :y iw :w ih :h} rect
        pct     (when (and pct-base (pos? pct-base))
                  (* 100 (/ value pct-base)))
        min-dim (min iw ih)
        hv      @hovered-id
        is-hovered? (= hv id)
        is-selected? (and selected-id (= (str id) (str selected-id)))
        highlight-id (or selected-id hv)
        is-highlighted? (if selected-id is-selected? is-hovered?)
        opacity (if (some? highlight-id) (if is-highlighted? 1 0.45) 1)
        styles (if unallocated? (unallocated-label-styles min-dim) (label-styles min-dim))]
    ^{:key id}
    [:div {:class (when unallocated? "target-treemap-unallocated")
           :style {:position         "absolute"
                   :left             (str (+ ix 1) "px")
                   :top              (str (+ iy 1) "px")
                   :width            (str (max 0 (- iw 2)) "px")
                   :height           (str (max 0 (- ih 2)) "px")
                   :box-sizing       "border-box"
                   :overflow         "hidden"
                   :border-radius    "8px"
                   :cursor           (if unallocated? "default" "pointer")
                   :transition       "left 0.2s ease, top 0.2s ease, width 0.2s ease, height 0.2s ease, opacity 0.15s ease"
                   :opacity          opacity
                   :background-color (when-not unallocated? (or color "#9ca3af"))}
           :on-mouse-enter #(reset! hovered-id id)
           :on-mouse-leave #(reset! hovered-id nil)
           :on-click (when-not unallocated?
                       #(dispatch [:edit-category3 (str id) 0]))
           :title (str name " — " (when pct (gstring/format "%.1f%%" pct))
                       " (" (fmt/format-amount value) ")") }
     [:div {:style {:position "absolute" :inset "0"
                    :display "flex" :flex-direction "column"
                    :align-items "center" :justify-content "center"
                    :pointer-events "none"
                    :overflow "hidden"}}
      (when (> min-dim 20)
        [:span {:style (:name styles)} name])
      (when (and pct (> min-dim 40))
        [:span {:style (:pct styles)}
         (gstring/format "%.1f%%" pct)])]]))

(defn- budget-expense-cats [categories]
  (->> categories
       (filter :name)
       (remove #(layout/excluded-ids (:id %)))
       (remove #(pos? (or (:amount %) 0)))))

(defn target-treemap
  "Treemap of category targets on the budget page.

   Cells are sized by each category's monthly target. An optional frontend-only
   `:treemap-target` (totalTarget) is the visualization envelope: leftover
   budget appears as an 'Ufordelt' cell. Editing a target updates the map live.

   Props:
     :height-ratio – height = width × ratio (default 0.32)"
  []
  (let [width-atom (r/atom nil)
        obs-atom   (r/atom nil)
        hovered-id (r/atom nil)
        bind-ref!  (fn [el]
                     (when-let [old @obs-atom]
                       (.disconnect old)
                       (reset! obs-atom nil))
                     (when el
                       (let [obs (js/ResizeObserver.
                                  (fn [entries]
                                    (when-let [e (aget entries 0)]
                                      (reset! width-atom (.-width (.-contentRect e))))))]
                         (.observe obs el)
                         (reset! obs-atom obs))))]
    (fn [{:keys [height-ratio] :or {height-ratio 0.32}}]
      (let [categories    @(subscribe [:summed-categories])
            total-target  @(subscribe [:treemap-target])
            builder       @(subscribe [:builder-category])
            selected-id   (:id builder)
            expense-cats  (budget-expense-cats categories)
            items         (layout/build-target-items expense-cats total-target)
            allocated-sum (reduce + 0 (map :value (remove :unallocated? items)))
            envelope      (parse-target total-target)
            remainder     (or (some :value (filter :unallocated? items)) 0)
            over          (when envelope (max 0 (- allocated-sum envelope)))
            pct-base      (reduce + 0 (map :value items))
            cw            @width-atom
            ch            (when cw (* cw height-ratio))
            va            (when (and cw ch (pos? pct-base)) (* cw ch))
            laid-out      (when (and va (seq items))
                            (layout/squarify
                             (mapv (fn [c] (assoc c :area (* va (/ (:value c) pct-base))))
                                   items)
                             {:x 0 :y 0 :w cw :h ch}))
            rects         (when laid-out
                            (doall
                             (for [item laid-out]
                               ^{:key (:id item)}
                               [target-treemap-rect item (:rect item) pct-base
                                hovered-id selected-id])))]
        [:div.target-treemap-card
         [:div.target-treemap-toolbar
          [:span.target-treemap-title "Målførdeling"]
          [:label.target-treemap-total
           [:span.dim "Totalt mål"]
           [:input.be-input.a-right
            {:type "text"
             :value (or total-target "")
             :placeholder "—"
             :on-change #(dispatch [:set-treemap-target (-> % .-target .-value)])}]
           [:span.dim "kr"]]
          [:div.target-treemap-meta
           [:span
            "Tildelt "
            [:span {:style {:font-weight "600" :color "var(--text)"}}
             (fmt/format-amount allocated-sum)]
            (when envelope
              [:span.dim (str " / " (fmt/format-amount envelope))])]
           (cond
             (and envelope (pos? remainder))
             [:span.dim (str (fmt/format-amount remainder) " ufordelt")]
             (and envelope (pos? over))
             [:span.neg (str (fmt/format-amount over) " over totalt mål")])]]
         [:div {:ref bind-ref!
                :style {:width        "100%"
                        :aspect-ratio (when (seq items) (/ 1 height-ratio))
                        :height       (when (and (seq items) ch) (str ch "px"))
                        :min-height   (when (empty? items) "72px")
                        :position     "relative"
                        :overflow     "hidden"
                        :box-sizing   "border-box"}}
          (if (seq items)
            rects
            [:div.target-treemap-empty
             "Sett et totalt mål, eller gi kategoriene et mål, for å se fordelingen."])]]))))
