(ns client.components.ui.icon)

(def ^:private paths
  {:caret-right  "M9 6l6 6-6 6"
   :caret-down   "M6 9l6 6 6-6"
   :plus         "M12 5v14M5 12h14"
   :close        "M18 6L6 18M6 6l12 12"
   :sun          "M12 3v2M12 19v2M5 12H3M21 12h-2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4l1.4-1.4M17 7l1.4-1.4"
   :moon         "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"
   :search       "M11 19a8 8 0 1 1 0-16 8 8 0 0 1 0 16zM21 21l-4.3-4.3"
   :check        "M5 13l4 4 10-12"
   :dot          "M12 12h.01"
   :arrow-up     "M5 12l7-7 7 7M12 5v14"
   :arrow-down   "M5 12l7 7 7-7M12 5v14"
   :settings     "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"
   :menu         "M4 6h16M4 12h16M4 18h16"
   :trash        "M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"
   :edit         "M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7M18.4 2.6a2 2 0 0 1 2.8 2.8L12 14.6 8 16l1.4-4 9-9.4z"
   :eye          "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"
   :wallet       "M3 7a2 2 0 0 1 2-2h13v4h-2a2 2 0 0 0 0 4h2v4H5a2 2 0 0 1-2-2V7zM16 11a1 1 0 1 0 0 2 1 1 0 0 0 0-2z"
   :pie          "M21 15a9 9 0 1 1-9-9v9h9z"
   :list         "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"
   :bars         "M3 21V10M9 21V4M15 21v-8M21 21v-15"
   :trend-up     "M3 17l6-6 4 4 8-8M14 7h7v7"
   :tag          "M3 12V5a2 2 0 0 1 2-2h7l9 9-9 9-9-9z"
   :card         "M3 6h18v12H3zM3 10h18"
   :file         "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zM14 2v6h6"
   :cart         "M6 6h15l-1.5 9h-12zM6 6L5 3H2M9 20a1 1 0 1 0 0.01 0M18 20a1 1 0 1 0 0.01 0"})

(defn icon
  "Inline SVG icon. Pass a keyword name and optional opts {:size :class :stroke}."
  ([name] (icon name {}))
  ([name {:keys [size class stroke]
          :or {size 16 stroke 1.5 class ""}}]
   (let [d (get paths name)]
     [:svg {:width size :height size :viewBox "0 0 24 24"
            :fill "none" :stroke "currentColor" :stroke-width stroke
            :stroke-linecap "round" :stroke-linejoin "round"
            :class class}
      [:path {:d d}]])))
