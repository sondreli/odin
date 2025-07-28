(ns client.components.chart-component.views
  (:require [re-frame.core :refer [dispatch subscribe]]))

(defn stacked-barchart [displayed-transactions categories period chart-size]
  [:div
   [:button {:on-click #(dispatch [:toggle-chart-size])} "Toggle positive kategorier"]
   [:div
    {:id "mychart" :style {:height "800px"}}
    (dispatch [:draw-stacked-barchart displayed-transactions categories period chart-size])]])
