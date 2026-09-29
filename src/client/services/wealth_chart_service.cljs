(ns client.services.wealth-chart-service
  "Self-contained D3 bar chart for the Formue (wealth) page.
   x = month, y = portfolio value (NOK), bar fill = appreciation (red→green diverging).

   Trade attribution:
   - buy months get a colored segment at the top of the bar (part of the value);
   - sell months get a dashed ghost segment above the bar (counterfactual 'what could have been');
   - hovering a segment or its ▲/▼ marker fades the other bars and propagates that month's
     contribution across all future bars (buys highlighted at the bottom, sells added on top)."
  (:require ["d3" :as d3]
            [goog.object :as g]
            [client.services.format-service :as fmt]))

(def ^:private width 920)
(def ^:private height 460)
(def ^:private margin {:top 44 :right 16 :bottom 56 :left 64})
(def ^:private buy-color "#2563eb")
(def ^:private sell-color "#f59e0b")
(def ^:private lev-cap-color "#9ca3af")   ;; collateral capacity (light grey)
(def ^:private lev-used-color "#b91c1c")  ;; margin used (dark red)

(defn- clear! [container-id]
  (-> d3 (.select (str container-id " svg")) (.remove))
  (-> d3 (.selectAll ".wealth-tooltip") (.remove)))

(defn- color-for
  "Map an appreciation ratio (e.g. 0.25 = +25%) to a diverging color, symmetric about 0.
   nil (undefined appreciation) → neutral grey."
  [appr max-abs]
  (if (nil? appr)
    "#c4c4c4"
    (let [t (-> (/ (+ appr max-abs) (* 2.0 max-abs))
                (max 0.0) (min 1.0))]
      (d3/interpolateRdYlGn t))))

(defn- fmt-int [n]
  (-> (js/Math.round n) (.toLocaleString "nb-NO")))

(defn draw-wealth-chart
  "Render the wealth chart into `container-id`.
   - points:     [{:date :value :appreciation}]
   - buy-bands:  [{:cohort \"YYYY-MM\" :points [{:date :value} …]}]  (FIFO-aware remaining value)
   - sell-bands: [{:cohort \"YYYY-MM\" :points [{:date :value} …]}]  (counterfactual value)
   - trades:     [{:month :type :quantity :amount-nok}]              (marker tooltips)
   - leverage:   [{:date :max :used}]  (drawn as negative bars below the 0 line)"
  [container-id points buy-bands sell-bands trades leverage]
  (clear! container-id)
  (let [points (vec points)
        buy-bands (vec buy-bands)
        sell-bands (vec sell-bands)
        leverage (vec leverage)
        data (clj->js points)
        floor (- height (:bottom margin))
        bw-pad 0.15
        value-map (into {} (map (juxt :date :value) points))
        buy-band-map (into {} (map (fn [b] [(:cohort b) (:points b)]) buy-bands))
        sell-band-map (into {} (map (fn [b] [(:cohort b) (:points b)]) sell-bands))
        max-val (or (d3/max data (fn [d] (g/get d "value"))) 1)
        ;; headroom so a hovered sell counterfactual (value + band) always fits
        sell-maps (map (fn [b] (into {} (map (juxt :date :value) (:points b)))) sell-bands)
        max-stack (reduce (fn [m p]
                            (let [d (:date p)
                                  extra (reduce (fn [a sm] (max a (get sm d 0.0))) 0.0 sell-maps)]
                              (max m (+ (:value p) extra))))
                          max-val points)
        max-lev (reduce (fn [m l] (max m (or (:max l) 0.0))) 0.0 leverage)
        apprs (->> points (keep :appreciation) (map #(js/Math.abs %)))
        max-abs (max 0.05 (if (seq apprs) (apply max apprs) 0.05))
        x (-> d3 .scaleBand
              (.domain (.map data (fn [d] (g/get d "date"))))
              (.range (clj->js [(:left margin) (- width (:right margin))]))
              (.padding bw-pad))
        bw (.bandwidth x)
        y (-> d3 .scaleLinear
              (.domain (clj->js [(if (pos? max-lev) (- (* 1.05 max-lev)) 0)
                                 (* 1.05 max-stack)]))
              (.range (clj->js [floor (:top margin)])))
        baseline (y 0)
        svg (-> d3 (.select container-id)
                (.append "svg")
                (.attr "width" width)
                (.attr "height" height)
                (.attr "viewBox" (clj->js [0 0 width height]))
                (.attr "style" "max-width:100%;height:auto;font:11px sans-serif;"))
        tip (-> d3 (.select "body")
                (.append "div")
                (.attr "class" "wealth-tooltip tooltip-barchart")
                (.style "opacity" "0"))
        show-tip (fn [event html]
                   (-> tip (.transition) (.duration 80) (.style "opacity" "1"))
                   (-> tip (.html html)
                       (.style "left" (str (+ 12 (.-pageX event)) "px"))
                       (.style "top" (str (- (.-pageY event) 12) "px"))))
        hide-tip (fn [] (-> tip (.transition) (.duration 120) (.style "opacity" "0")))
        n (count points)
        tick-step (max 1 (js/Math.ceil (/ n 14)))
        x-axis (-> (d3/axisBottom x)
                   (.tickValues (clj->js (->> points (map :date) (map-indexed vector)
                                              (filter (fn [[i _]] (zero? (mod i tick-step))))
                                              (map second) vec)))
                   (.tickSizeOuter 0))
        ;; --- hover propagation ---
        clear-highlight! (fn []
                           (-> svg (.selectAll ".highlight-layer") (.remove))
                           (-> svg (.selectAll ".base-bar") (.attr "opacity" 1))
                           (-> svg (.selectAll ".diff-seg") (.attr "opacity" 1)))
        highlight! (fn [cohort type]
                     (clear-highlight!)
                     (-> svg (.selectAll ".base-bar") (.attr "opacity" 0.3))
                     (-> svg (.selectAll ".diff-seg") (.attr "opacity" 0.25))
                     (let [gl (-> svg (.append "g") (.attr "class" "highlight-layer")
                                  (.attr "pointer-events" "none"))]
                       (if (= type "buy")
                         (when-let [pts (get buy-band-map cohort)]
                           ;; buy contribution highlighted at the bottom of each future bar
                           (-> gl (.selectAll "rect") (.data (clj->js pts)) (.join "rect")
                               (.attr "x" (fn [d] (x (g/get d "date"))))
                               (.attr "width" bw)
                               (.attr "y" (fn [d] (y (g/get d "value"))))
                               (.attr "height" (fn [d] (max 0 (- baseline (y (g/get d "value"))))))
                               (.attr "rx" 2)
                               (.attr "fill" buy-color)
                               (.attr "opacity" 0.95)))
                         (when-let [pts (get sell-band-map cohort)]
                           ;; sell counterfactual added on top of each future bar
                           (let [aug (clj->js (mapv (fn [p] {:date (:date p) :band (:value p)
                                                             :bar (get value-map (:date p) 0.0)})
                                                    pts))]
                             (-> gl (.selectAll "rect") (.data aug) (.join "rect")
                                 (.attr "x" (fn [d] (x (g/get d "date"))))
                                 (.attr "width" bw)
                                 (.attr "y" (fn [d] (y (+ (g/get d "bar") (g/get d "band")))))
                                 (.attr "height" (fn [d] (max 0 (- (y (g/get d "bar"))
                                                                   (y (+ (g/get d "bar") (g/get d "band")))))))
                                 (.attr "rx" 2)
                                 (.attr "fill" sell-color)
                                 (.attr "opacity" 0.5)
                                 (.attr "stroke" sell-color)
                                 (.attr "stroke-dasharray" "3,2")))))))]
    ;; base bars (full value, colored by appreciation)
    (-> svg (.append "g")
        (.selectAll "rect")
        (.data data)
        (.join "rect")
        (.attr "class" "base-bar")
        (.attr "x" (fn [d] (x (g/get d "date"))))
        (.attr "y" (fn [d] (y (g/get d "value"))))
        (.attr "width" bw)
        (.attr "height" (fn [d] (max 0 (- baseline (y (g/get d "value"))))))
        (.attr "rx" 2)
        (.attr "fill" (fn [d] (color-for (let [a (g/get d "appreciation")] (when (some? a) a)) max-abs)))
        (.on "mouseover"
             (fn [event d]
               (let [a (g/get d "appreciation")
                     appr-txt (if (some? a) (str (when (>= a 0) "+") (.toFixed (* 100 a) 1) " %") "–")]
                 (show-tip event (str "<strong>" (g/get d "date") "</strong><br/>"
                                      (fmt/format-kr (js/Math.round (g/get d "value"))) "<br/>"
                                      "Verdiendring: " appr-txt)))))
        (.on "mouseout" (fn [_ _] (hide-tip))))
    ;; leverage: capacity + used as negative bars below the zero line
    (when (and (pos? max-lev) (seq leverage))
      (let [lev (clj->js (vec (filter #(> (or (:max %) 0) 0) leverage)))]
        ;; capacity (light) — carries the tooltip for the whole region
        (-> svg (.append "g")
            (.selectAll "rect")
            (.data lev)
            (.join "rect")
            (.attr "x" (fn [d] (x (g/get d "date"))))
            (.attr "width" bw)
            (.attr "y" baseline)
            (.attr "height" (fn [d] (max 0 (- (y (- (g/get d "max"))) baseline))))
            (.attr "fill" lev-cap-color)
            (.attr "opacity" 0.3)
            (.style "cursor" "pointer")
            (.on "mouseover"
                 (fn [event d]
                   (let [mx (g/get d "max") used (g/get d "used")
                         conc (g/get d "concentration") eff (g/get d "effective-rate")
                         util (if (> mx 0) (* 100.0 (/ used mx)) 0)]
                     (show-tip event (str "<strong>" (g/get d "date") "</strong><br/>"
                                          "Maks belåning: " (fmt/format-kr (js/Math.round mx)) "<br/>"
                                          "Brukt: " (fmt/format-kr (js/Math.round used))
                                          " (" (.toFixed util 0) " %)"
                                          (when (some? conc)
                                            (str "<br/>Største posisjon: " (.toFixed (* 100 conc) 0) " %"
                                                 " → effektiv belåningsgrad " (.toFixed (* 100 eff) 0) " %")))))))
            (.on "mouseout" (fn [_ _] (hide-tip))))
        ;; used (solid, on top)
        (-> svg (.append "g")
            (.selectAll "rect")
            (.data lev)
            (.join "rect")
            (.attr "x" (fn [d] (x (g/get d "date"))))
            (.attr "width" bw)
            (.attr "y" baseline)
            (.attr "height" (fn [d] (max 0 (- (y (- (g/get d "used"))) baseline))))
            (.attr "fill" lev-used-color)
            (.attr "pointer-events" "none"))))
    ;; zero line
    (-> svg (.append "line")
        (.attr "x1" (:left margin)) (.attr "x2" (- width (:right margin)))
        (.attr "y1" baseline) (.attr "y2" baseline)
        (.attr "stroke" "#999") (.attr "stroke-width" 1))
    ;; at-rest buy diff segments (top of the buy month's bar)
    (let [buy-diffs (clj->js
                     (vec (for [b buy-bands
                                :let [j (:cohort b)
                                      diff (:value (first (:points b)))
                                      bar (get value-map j)]
                                :when (and bar diff (> diff 1e-6))]
                            {:month j :diff diff :bar bar})))]
      (-> svg (.append "g")
          (.selectAll "rect")
          (.data buy-diffs)
          (.join "rect")
          (.attr "class" "diff-seg")
          (.style "cursor" "pointer")
          (.attr "x" (fn [d] (x (g/get d "month"))))
          (.attr "width" bw)
          (.attr "y" (fn [d] (y (g/get d "bar"))))
          (.attr "height" (fn [d] (max 0 (- (y (- (g/get d "bar") (g/get d "diff"))) (y (g/get d "bar"))))))
          (.attr "fill" buy-color)
          (.attr "stroke" "white")
          (.attr "stroke-width" 0.6)
          (.on "mouseover" (fn [event d]
                             (highlight! (g/get d "month") "buy")
                             (show-tip event (str "<strong>" (g/get d "month") "</strong><br/>Kjøpt: "
                                                  (fmt/format-kr (js/Math.round (g/get d "diff")))))))
          (.on "mouseout" (fn [_ _] (clear-highlight!) (hide-tip)))))
    ;; at-rest sell ghost segments (above the sell month's bar)
    (let [sell-ghosts (clj->js
                       (vec (for [b sell-bands
                                  :let [j (:cohort b)
                                        ghost (:value (first (:points b)))
                                        bar (get value-map j)]
                                  :when (and bar ghost (> ghost 1e-6))]
                              {:month j :ghost ghost :bar bar})))]
      (-> svg (.append "g")
          (.selectAll "rect")
          (.data sell-ghosts)
          (.join "rect")
          (.attr "class" "diff-seg")
          (.style "cursor" "pointer")
          (.attr "x" (fn [d] (x (g/get d "month"))))
          (.attr "width" bw)
          (.attr "y" (fn [d] (y (+ (g/get d "bar") (g/get d "ghost")))))
          (.attr "height" (fn [d] (max 0 (- (y (g/get d "bar")) (y (+ (g/get d "bar") (g/get d "ghost")))))))
          (.attr "fill" sell-color)
          (.attr "opacity" 0.25)
          (.attr "stroke" sell-color)
          (.attr "stroke-dasharray" "3,2")
          (.on "mouseover" (fn [event d]
                             (highlight! (g/get d "month") "sell")
                             (show-tip event (str "<strong>" (g/get d "month") "</strong><br/>Solgt (potensial): "
                                                  (fmt/format-kr (js/Math.round (g/get d "ghost")))))))
          (.on "mouseout" (fn [_ _] (clear-highlight!) (hide-tip)))))
    ;; x axis (date labels at the floor, below any leverage bars)
    (-> svg (.append "g")
        (.attr "transform" (str "translate(0," floor ")"))
        (.call x-axis)
        (.call (fn [gg]
                 (-> gg (.selectAll ".domain") (.remove))
                 (-> gg (.selectAll ".tick text")
                     (.attr "transform" "rotate(-45)")
                     (.style "text-anchor" "end")))))
    ;; y axis
    (-> svg (.append "g")
        (.attr "transform" (str "translate(" (:left margin) ",0)"))
        (.call (-> (d3/axisLeft y) (.ticks 6) (.tickFormat (d3/format "~s"))))
        (.call (fn [gg] (-> gg (.selectAll ".domain") (.remove)))))
    ;; buy/sell triangle markers in a lane above the bars
    (let [dom (set (map :date points))
          lane-y 20
          tri (-> (d3/symbol) (.type (.-symbolTriangle d3)) (.size 48))
          by-month (group-by :month (filter #(contains? dom (:month %)) trades))
          markers (clj->js
                   (vec (for [[month ts] by-month
                              t ts
                              :let [both? (= 2 (count (distinct (map :type ts))))
                                    base (+ (x month) (/ bw 2))
                                    off (if both? (if (= "buy" (:type t)) -6 6) 0)]]
                          {:cx (+ base off) :type (:type t) :month month
                           :qty (:quantity t) :amt (:amount-nok t)})))]
      (-> svg (.append "g")
          (.selectAll "path.trade-marker")
          (.data markers)
          (.join "path")
          (.attr "class" "trade-marker")
          (.attr "d" (tri))
          (.attr "transform" (fn [d] (str "translate(" (g/get d "cx") "," lane-y ")"
                                          (when (= "sell" (g/get d "type")) " rotate(180)"))))
          (.attr "fill" (fn [d] (if (= "buy" (g/get d "type")) buy-color sell-color)))
          (.attr "stroke" "white")
          (.attr "stroke-width" 0.8)
          (.style "cursor" "pointer")
          (.on "mouseover"
               (fn [event d]
                 (let [buy? (= "buy" (g/get d "type"))]
                   (highlight! (g/get d "month") (g/get d "type"))
                   (show-tip event (str "<strong>" (g/get d "month") "</strong><br/>"
                                        (if buy? "Kjøp" "Salg") ": "
                                        (fmt-int (g/get d "qty")) " stk<br/>"
                                        (fmt-int (js/Math.abs (g/get d "amt"))) " kr")))))
          (.on "mouseout" (fn [_ _] (clear-highlight!) (hide-tip)))))
    svg))
