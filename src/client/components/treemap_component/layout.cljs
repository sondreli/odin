(ns client.components.treemap-component.layout
  "Pure layout algorithm for squarified treemaps. Shared between web and mobile.")

(def excluded-ids #{"in" "out" "ukategorisert-in"})

(defn parse-target [t]
  (when t
    (let [n (if (number? t) t (js/parseFloat (str t)))]
      (when (and (number? n) (not (js/isNaN n)) (pos? n)) n))))

(defn darken-color
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

(defn lighten-color
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

(defn aspect-ratio [w h]
  (if (or (<= w 0) (<= h 0))
    js/Infinity
    (max (/ w h) (/ h w))))

(defn worst-ratio
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

(defn layout-strip
  "Position `items` as a strip inside `rect`.
   Returns [positioned-items remaining-rect]."
  [items rect]
  (let [{:keys [x y w h]} rect
        total-area (reduce + 0 (map :area items))]
    (if (>= w h)
      (let [sw (/ total-area h)
            [_ positioned]
            (reduce (fn [[cy acc] item]
                      (let [ih (/ (:area item) sw)]
                        [(+ cy ih)
                         (conj acc (assoc item :rect {:x x :y cy :w sw :h ih}))]))
                    [y []] items)]
        [positioned {:x (+ x sw) :y y :w (- w sw) :h h}])
      (let [sh (/ total-area w)
            [_ positioned]
            (reduce (fn [[cx acc] item]
                      (let [iw (/ (:area item) sh)]
                        [(+ cx iw)
                         (conj acc (assoc item :rect {:x cx :y y :w iw :h sh}))]))
                    [x []] items)]
        [positioned {:x x :y (+ y sh) :w w :h (- h sh)}]))))

(defn squarify
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
