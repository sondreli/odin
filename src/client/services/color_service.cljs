(ns client.services.color-service
  [:require [clojure.string :as s]])

(defn hsv2rgb
  "hue, saturation, value in the set [0, 1]"
  [[h s v]]
  (let [i (-> h (* 6) (Math/floor))
        f (-> h (* 6) (- i))
        p (-> 1 (- s) (* v))
        q (-> 1 (- (* f s)) (* v))
        t (-> 1 (- (* (- 1 f) s)) (* v))
        combo (case (mod i 6)
                0.0 [v t p]
                1.0 [q v p]
                2.0 [p v t]
                3.0 [p q v]
                4.0 [t p v]
                5.0 [v p q])]
    (map #(-> % (* 255) (Math/round)) combo)))

(defn color-base10->base16 [code]
  (let [make-size-2 (fn [s] (if (-> s count (= 1)) (str "0" s) s))]
    (map #(-> % (.toString 16) make-size-2) code)))

(defn color-str [rgb]
  (str "#" (s/join rgb)))

(defn sub-steps [i]
  (let [power (Math/pow 2 i)
        step (/ 1 power)
        num-steps (/ 1 step)
        all-steps (->> (range 1 (inc num-steps))
                       (map (fn [idx] [idx (* idx step)]))
                       (filter (fn [[idx itm]] (not= (rem idx 2) 0)))
                       (map (fn [[idx itm]] itm)))
        ]
    all-steps))

(defn log2 [n]
  (/ (Math/log n) (Math/log 2)))

(defn generate-hues [n]
  (let [base-log (Math/floor (log2 n))
        rest (rem n (Math/pow 2 base-log))
        sets (map sub-steps (range (+ 2 base-log)))
        start (butlast sets)
        end (take rest (last sets))]
    (mapcat identity (concat start [end]))))

(generate-hues 12)