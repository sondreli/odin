(ns client.components.ui.segmented)

(defn segmented
  "Pill segmented control. Renders the `.seg` / `.seg-btn` classes from friendly.css.

   Props (single map):
     :options    vector of [value label] pairs (label may be string or hiccup)
     :selected   currently selected value
     :on-select  (fn [value])
     :class      extra wrapper class

   Identity comparison is `=`."
  [{:keys [options selected on-select class]}]
  [:div {:class (str "seg " (or class ""))}
   (for [[i [value label]] (map-indexed vector options)]
     ^{:key (str value "-" i)}
     [:button {:class (str "seg-btn" (when (= value selected) " is-active"))
               :on-click #(on-select value)}
      label])])
