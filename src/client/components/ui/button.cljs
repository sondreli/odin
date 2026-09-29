(ns client.components.ui.button)

(defn- variant->class [variant]
  (case variant
    :primary    "btn-primary"
    :ghost      "btn-ghost"
    :primary-xs "btn-primary-xs"
    :ghost-xs   "btn-ghost-xs"
    :danger     "btn-danger"
    "btn-ghost"))

(defn pill-button
  "Reagent pill-button. opts:
     :variant   :primary | :ghost | :primary-xs | :ghost-xs | :danger (default :ghost)
     :on-click  fn
     :disabled  bool
     :class     extra class string
     :title     tooltip"
  [{:keys [variant on-click disabled class title]
    :or {variant :ghost class ""}}
   & children]
  (into [:button
         {:class (str (variant->class variant) " " class)
          :on-click on-click
          :disabled disabled
          :title title}]
        children))
