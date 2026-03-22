(ns client.components.treemap-component.views
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch]]
            [goog.string :as gstring]
            [goog.string.format]))

(defn- parse-target [t]
  (when t
    (let [n (if (number? t) t (js/parseFloat (str t)))]
      (when (and (number? n) (not (js/isNaN n)) (pos? n)) n))))

;; ---------- Squarified Treemap Layout ----------
;;
;; Produces rectangles whose aspect ratios are as close to 1 (square)
;; as possible. Larger items end up on the left, smaller on the right.

(defn- aspect-ratio [w h]
  (if (or (<= w 0) (<= h 0))
    js/Infinity
    (max (/ w h) (/ h w))))

(defn- worst-ratio
  "Worst aspect ratio among `items` if laid out as a single strip in `rect`."
  [items rect]
  (let [{rw :w rh :h} rect
        total-area (reduce + 0 (map :area items))]
    (if (or (<= total-area 0) (empty? items))
      js/Infinity
      (if (>= rw rh)
        (let [sw (/ total-area rh)]
          (reduce max 1 (map #(aspect-ratio sw (/ (:area %) sw)) items)))
        (let [sh (/ total-area rw)]
          (reduce max 1 (map #(aspect-ratio (/ (:area %) sh) sh) items)))))))

(defn- layout-strip
  "Position `items` as a strip inside `rect`.
   Returns [positioned-items remaining-rect]."
  [items rect]
  (let [{:keys [x y w h]} rect
        total-area (reduce + 0 (map :area items))]
    (if (>= w h)
      ;; landscape -> vertical strip on the left
      (let [sw (/ total-area h)
            [_ positioned]
            (reduce (fn [[cy acc] item]
                      (let [ih (/ (:area item) sw)]
                        [(+ cy ih)
                         (conj acc (assoc item :rect {:x x :y cy :w sw :h ih}))]))
                    [y []] items)]
        [positioned {:x (+ x sw) :y y :w (- w sw) :h h}])
      ;; portrait -> horizontal strip on the top
      (let [sh (/ total-area w)
            [_ positioned]
            (reduce (fn [[cx acc] item]
                      (let [iw (/ (:area item) sh)]
                        [(+ cx iw)
                         (conj acc (assoc item :rect {:x cx :y y :w iw :h sh}))]))
                    [x []] items)]
        [positioned {:x x :y (+ y sh) :w w :h (- h sh)}]))))

(defn- squarify
  "Lay out `items` (must have :area, sorted descending) into `rect`
   using the squarified treemap algorithm.
   Returns items with :rect {:x :y :w :h} added."
  [items rect]
  (when (seq items)
    (loop [row       [(first items)]
           remaining (rest items)
           rect      rect
           result    []]
      (if (empty? remaining)
        (let [[positioned _] (layout-strip row rect)]
          (into result positioned))
        (let [candidate (conj row (first remaining))
              cur-worst (worst-ratio row rect)
              cand-worst (worst-ratio candidate rect)]
          (if (<= cand-worst cur-worst)
            (recur candidate (rest remaining) rect result)
            (let [[positioned new-rect] (layout-strip row rect)]
              (recur [(first remaining)] (rest remaining) new-rect
                     (into result positioned)))))))))

;; ---------- Component ----------

(def ^:private excluded-ids #{"in" "out" "ukategorisert-in"})

(defn- darken-color
  "Make a hex color darker by mixing with black."
  [color-str factor]
  (try
    (let [hex (subs color-str 1)
          r (js/parseInt (subs hex 0 2) 16)
          g (js/parseInt (subs hex 2 4) 16)
          b (js/parseInt (subs hex 4 6) 16)
          dr (int (* r factor))
          dg (int (* g factor))
          db (int (* b factor))]
      (str "#"
           (.padStart (.toString dr 16) 2 "0")
           (.padStart (.toString dg 16) 2 "0")
           (.padStart (.toString db 16) 2 "0")))
    (catch :default _ color-str)))

(defn- lighten-color
  "Make a hex color lighter by mixing with white."
  [color-str factor]
  (try
    (let [hex (subs color-str 1)
          r (js/parseInt (subs hex 0 2) 16)
          g (js/parseInt (subs hex 2 4) 16)
          b (js/parseInt (subs hex 4 6) 16)
          lr (int (+ r (* (- 255 r) factor)))
          lg (int (+ g (* (- 255 g) factor)))
          lb (int (+ b (* (- 255 b) factor)))]
      (str "#"
           (.padStart (.toString lr 16) 2 "0")
           (.padStart (.toString lg 16) 2 "0")
           (.padStart (.toString lb 16) 2 "0")))
    (catch :default _ color-str)))

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

(defn- treemap-rect [{:keys [id name color value target]} rect pct-base
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
        accent-color (if over-target?
                       (darken-color base-color 0.65)
                       (lighten-color base-color 0.5))
        styles (label-styles min-dim)]
    ^{:key id}
    [:div {:style {:position         "absolute"
                   :left             (str ix "px")
                   :top              (str iy "px")
                   :width            (str iw "px")
                   :height           (str ih "px")
                   :border           "1px solid rgba(255,255,255,0.5)"
                   :box-sizing       "border-box"
                   :overflow         "hidden"
                   :cursor           "pointer"
                   :transition       "opacity 0.15s ease"
                   :opacity          opacity}
           :on-mouse-enter #(reset! hovered-id id)
           :on-mouse-leave #(reset! hovered-id nil)
           :on-click #(dispatch [:view-category name])
           :title (str name " — " (when pct (gstring/format "%.1f%%" pct))
                       " (" (gstring/format "%.0f" value) ")"
                       (when cat-target (str " target: " (gstring/format "%.0f" cat-target))))}
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
           [:div {:style (merge {:background-color accent-color}
                                (if horizontal?
                                  {:width accent-pct :height "100%"}
                                  {:height accent-pct :width "100%"}))}]]
          [:div {:style {:position "absolute" :inset "0"
                         :display "flex" :flex-direction "column"
                         :align-items "center" :justify-content "center"
                         :pointer-events "none"}}
           (when (> min-dim 20)
             [:span {:style (:name styles)} name])
           (when (and pct (> min-dim 40))
             [:span {:style (:pct styles)}
              (gstring/format "%.1f%%" pct)])]])
       [:div {:style {:background-color base-color
                      :width "100%" :height "100%"
                      :display "flex" :flex-direction "column"
                      :align-items "center" :justify-content "center"
                      :overflow "hidden"}}
        (when (> min-dim 20)
          [:span {:style (:name styles)} name])
        (when (and pct (> min-dim 40))
          [:span {:style (:pct styles)}
           (gstring/format "%.1f%%" pct)])])]))

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
        hovered-id    (r/atom nil)]
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
      (fn [{:keys [categories pct-base show-targets? height-ratio]
            :or   {height-ratio 0.5 show-targets? false}}]
        (let [cw @width-atom
              ch (when cw (* cw height-ratio))
              filter-path @(subscribe [:filter-path])
              selected-name (when (= 1 (count filter-path)) (first filter-path))

              cats (->> categories
                        (remove #(excluded-ids (:id %)))
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

              total-value (reduce + 0
                                  (map #(if show-targets?
                                          (max (:value %) (or (:target %) 0))
                                          (:value %))
                                       cats))
              vw (when (and cw (pos? total-value)) cw)
              va (when (and vw ch) (* vw ch))

              items    (when va
                         (mapv (fn [c]
                                 (let [effective (if show-targets?
                                                   (max (:value c) (or (:target c) 0))
                                                   (:value c))]
                                   (assoc c :area (* va (/ effective total-value)))))
                               cats))
              laid-out (when (and items (seq items) (pos? vw) (pos? ch))
                         (squarify items {:x 0 :y 0 :w vw :h ch}))]

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
                [treemap-rect item (:rect item) pct-base
                 hovered-id selected-name show-targets?])))] ) ) } ) ))

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
      (str (if over? "+" "-") (gstring/format "%.0f" (Math/abs diff-value)))]]))

(defn category-treemap
  "Subscribes to :summed-categories. Renders a treemap at full width.
   Target arrow above and spending arrow below mark their positions.
   Checkbox toggles target sub-rectangles on/off.

   Props:
     :height-ratio – optional (default 0.5)"
  []
  (let [show-targets? (r/atom false)]
    (fn [{:keys [height-ratio] :or {height-ratio 0.5}}]
      (let [categories @(subscribe [:summed-categories])
            period     @(subscribe [:period])
            single-month? (= :month (:period-type period))
            targets?   (and single-month? @show-targets?)
            visible    (->> categories
                            (remove #(excluded-ids (:id %)))
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
            diff-value     (- total-spending target-sum)]
        [:div
         (when single-month?
           [:div {:style {:display "flex" :align-items "center" :gap "6px"
                          :margin-bottom "6px"}}
            [:label {:style {:display "flex" :align-items "center" :gap "4px"
                             :font-size "13px" :color "#555" :cursor "pointer"
                             :user-select "none"}}
             [:input {:type "checkbox"
                      :checked targets?
                      :on-change #(swap! show-targets? not)
                      :style {:cursor "pointer"}}]
             "Vis budsjett"]])
         (when targets?
           [:div {:style {:position "relative" :height "28px" :margin-bottom "2px"}}
            (when target-pct
              [treemap-arrow-above target-pct (gstring/format "%.0f" target-sum)])])
         [treemap {:categories    categories
                   :pct-base      pct-base
                   :show-targets? targets?
                   :height-ratio  height-ratio}]
         [:div {:style {:position "relative" :height "28px" :margin-top "2px"}}
          (when (and spending-pct (pos? total-spending))
            [treemap-arrow-below spending-pct (gstring/format "%.0f" total-spending)])]
         (when (and targets? target-pct spending-pct)
           [diff-bar target-pct spending-pct diff-value])]))))
