(ns client.services.report-chart-service
  (:require ["d3" :as d3]
            [goog.string :as gstring]
            [goog.string.format]))

(def ^:private margin {:top 20 :right 30 :bottom 40 :left 60})
(def ^:private chart-width 800)
(def ^:private chart-height 350)

(defn- clear-chart [element-id]
  (-> d3 (.select (str "#" element-id)) (.selectAll "svg") (.remove)))

(defn draw-bar-chart
  "Renders a simple bar chart into the DOM element with the given id.
   data-points: [{:label \"Jan\" :value 1234} ...]
   on-bar-click: optional (fn [label]) called when a bar is clicked."
  [element-id data-points & {:keys [on-bar-click]}]
  (clear-chart element-id)
  (when (seq data-points)
    (let [w (+ chart-width (:left margin) (:right margin))
          h (+ chart-height (:top margin) (:bottom margin))
          svg (-> d3
                  (.select (str "#" element-id))
                  (.append "svg")
                  (.attr "viewBox" (str "0 0 " w " " h))
                  (.attr "width" "100%")
                  (.append "g")
                  (.attr "transform" (str "translate(" (:left margin) "," (:top margin) ")")))
          labels (clj->js (map :label data-points))
          values (map :value data-points)
          max-val (apply max (map #(Math/abs %) values))
          min-val (apply min values)
          y-max (if (pos? max-val) (* 1.1 max-val) 10)
          y-min (if (neg? min-val) (* 1.1 min-val) 0)
          x (-> d3
                (.scaleBand)
                (.domain labels)
                (.range (clj->js [0 chart-width]))
                (.padding 0.2))
          y (-> d3
                (.scaleLinear)
                (.domain (clj->js [y-min y-max]))
                (.nice)
                (.range (clj->js [chart-height 0])))]

      ;; X axis
      (-> svg
          (.append "g")
          (.attr "transform" (str "translate(0," chart-height ")"))
          (.call (.axisBottom d3 x))
          (.selectAll "text")
          (.attr "transform" "rotate(-45)")
          (.style "text-anchor" "end")
          (.attr "dx" "-0.5em")
          (.attr "dy" "0.15em"))

      ;; Y axis
      (-> svg (.append "g") (.call (.axisLeft d3 y)))

      ;; Zero line
      (when (and (neg? y-min) (pos? y-max))
        (-> svg
            (.append "line")
            (.attr "x1" 0)
            (.attr "x2" chart-width)
            (.attr "y1" (y 0))
            (.attr "y2" (y 0))
            (.attr "stroke" "#999")
            (.attr "stroke-dasharray" "3,3")))

      ;; Bars
      (let [bars (-> svg
                     (.selectAll "rect.bar")
                     (.data (clj->js data-points))
                     (.join "rect")
                     (.attr "class" "bar")
                     (.attr "x" (fn [d] (x (.-label d))))
                     (.attr "width" (.bandwidth x))
                     (.attr "y" (fn [d] (let [v (.-value d)]
                                          (if (>= v 0) (y v) (y 0)))))
                     (.attr "height" (fn [d] (let [v (.-value d)]
                                               (Math/abs (- (y 0) (y v))))))
                     (.attr "fill" (fn [d] (if (>= (.-value d) 0) "#4f86c6" "#ef4444")))
                     (.attr "rx" 2))]
        (when on-bar-click
          (-> bars
              (.style "cursor" "pointer")
              (.on "click" (fn [_event d] (on-bar-click (.-label d)))))))

      ;; Value labels on bars
      (-> svg
          (.selectAll "text.val")
          (.data (clj->js data-points))
          (.join "text")
          (.attr "class" "val")
          (.attr "x" (fn [d] (+ (x (.-label d)) (/ (.bandwidth x) 2))))
          (.attr "y" (fn [d] (let [v (.-value d)]
                               (if (>= v 0) (- (y v) 4) (+ (y v) 14)))))
          (.attr "text-anchor" "middle")
          (.attr "font-size" "11px")
          (.attr "fill" "#333")
          (.text (fn [d] (gstring/format "%.0f" (.-value d))))))))

(defn draw-waterfall-chart
  "Renders a waterfall chart where each bar starts where the previous ended.
   data-points: [{:label \"Jan\" :value 1234} ...]
   on-bar-click: optional (fn [label]) called when a bar is clicked."
  [element-id data-points & {:keys [on-bar-click]}]
  (clear-chart element-id)
  (when (seq data-points)
    (let [waterfall-data (loop [pts data-points
                                acc []
                                running 0]
                           (if (empty? pts)
                             acc
                             (let [pt (first pts)
                                   v (:value pt)
                                   new-running (+ running v)]
                               (recur (rest pts)
                                      (conj acc {:label (:label pt)
                                                 :value v
                                                 :start running
                                                 :end new-running})
                                      new-running))))
          w (+ chart-width (:left margin) (:right margin))
          h (+ chart-height (:top margin) (:bottom margin))
          svg (-> d3
                  (.select (str "#" element-id))
                  (.append "svg")
                  (.attr "viewBox" (str "0 0 " w " " h))
                  (.attr "width" "100%")
                  (.append "g")
                  (.attr "transform" (str "translate(" (:left margin) "," (:top margin) ")")))
          all-vals (mapcat (fn [d] [(:start d) (:end d)]) waterfall-data)
          y-min (* 1.1 (apply min all-vals))
          y-max (* 1.1 (apply max all-vals))
          y-min (if (= y-min y-max) (- y-min 10) y-min)
          y-max (if (= y-min y-max) (+ y-max 10) y-max)
          labels (clj->js (map :label waterfall-data))
          x (-> d3
                (.scaleBand)
                (.domain labels)
                (.range (clj->js [0 chart-width]))
                (.padding 0.2))
          y (-> d3
                (.scaleLinear)
                (.domain (clj->js [y-min y-max]))
                (.nice)
                (.range (clj->js [chart-height 0])))]

      ;; X axis
      (-> svg
          (.append "g")
          (.attr "transform" (str "translate(0," chart-height ")"))
          (.call (.axisBottom d3 x))
          (.selectAll "text")
          (.attr "transform" "rotate(-45)")
          (.style "text-anchor" "end")
          (.attr "dx" "-0.5em")
          (.attr "dy" "0.15em"))

      ;; Y axis
      (-> svg (.append "g") (.call (.axisLeft d3 y)))

      ;; Zero line
      (-> svg
          (.append "line")
          (.attr "x1" 0)
          (.attr "x2" chart-width)
          (.attr "y1" (y 0))
          (.attr "y2" (y 0))
          (.attr "stroke" "#999")
          (.attr "stroke-dasharray" "3,3"))

      ;; Connector lines between bars
      (let [js-data (clj->js waterfall-data)]
        (-> svg
            (.selectAll "line.connector")
            (.data js-data)
            (.join "line")
            (.attr "class" "connector")
            (.attr "x1" (fn [d _i] (+ (x (.-label d)) (.bandwidth x))))
            (.attr "x2" (fn [_d i] (let [next-d (aget js-data (inc i))]
                                     (if next-d (x (.-label next-d)) 0))))
            (.attr "y1" (fn [d] (y (.-end d))))
            (.attr "y2" (fn [d] (y (.-end d))))
            (.attr "stroke" "#aaa")
            (.attr "stroke-dasharray" "2,2")
            (.style "display" (fn [_ i] (if (< i (dec (count waterfall-data))) "block" "none")))))

      ;; Bars
      (let [bars (-> svg
                     (.selectAll "rect.bar")
                     (.data (clj->js waterfall-data))
                     (.join "rect")
                     (.attr "class" "bar")
                     (.attr "x" (fn [d] (x (.-label d))))
                     (.attr "width" (.bandwidth x))
                     (.attr "y" (fn [d] (y (max (.-start d) (.-end d)))))
                     (.attr "height" (fn [d] (Math/abs (- (y (.-start d)) (y (.-end d))))))
                     (.attr "fill" (fn [d] (if (>= (.-value d) 0) "#4f86c6" "#ef4444")))
                     (.attr "rx" 2))]
        (when on-bar-click
          (-> bars
              (.style "cursor" "pointer")
              (.on "click" (fn [_event d] (on-bar-click (.-label d)))))))

      ;; Value labels
      (-> svg
          (.selectAll "text.val")
          (.data (clj->js waterfall-data))
          (.join "text")
          (.attr "class" "val")
          (.attr "x" (fn [d] (+ (x (.-label d)) (/ (.bandwidth x) 2))))
          (.attr "y" (fn [d] (let [top (min (.-start d) (.-end d))]
                               (- (y top) 4))))
          (.attr "text-anchor" "middle")
          (.attr "font-size" "11px")
          (.attr "fill" "#333")
          (.text (fn [d] (gstring/format "%.0f" (.-value d))))))))
