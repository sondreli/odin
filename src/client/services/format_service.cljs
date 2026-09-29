(ns client.services.format-service
  (:require [goog.string :as gstring]
            [goog.string.format]))

(def ^:private nbsp " ")

(def ^:private group-re
  (js/RegExp. "\\B(?=(\\d{3})+(?!\\d))" "g"))

(defn format-amount
  "Whole-kroner number with NBSP thousand separators. Negative numbers keep their
   sign. Returns nil for non-numbers."
  [n]
  (when (number? n)
    (let [whole   (gstring/format "%.0f" (Math/abs n))
          grouped (.replace whole group-re nbsp)]
      (if (neg? n) (str "-" grouped) grouped))))

(defn format-kr
  "format-amount with NBSP + 'kr' suffix."
  [n]
  (when (number? n) (str (format-amount n) nbsp "kr")))

(defn format-signed-kr
  "format-kr with an explicit '+' on positive numbers."
  [n]
  (when (number? n) (str (when (pos? n) "+") (format-kr n))))
