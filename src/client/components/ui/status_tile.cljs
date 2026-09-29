(ns client.components.ui.status-tile)

(defn status-tile
  "Status tile with label / value / optional delta line. Reads styles from friendly.css.

   Props:
     :label     string
     :value     string or hiccup
     :delta     optional {:pct number :up? bool}  — renders ▲/▼ + pct
     :accent    optional CSS color override for :value
     :class     extra wrapper class"
  [{:keys [label value delta accent class]}]
  [:div {:class (str "status-tile " (or class ""))}
   [:div.status-label label]
   [:div.status-value {:style (when accent {:color accent})} value]
   (when delta
     (let [{:keys [pct up?]} delta]
       [:div {:class (str "status-delta " (if up? "up" "down"))}
        (if up? "▲ " "▼ ")
        (when (number? pct) (str (.toFixed pct 1) "%"))]))])
