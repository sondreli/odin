(ns client.components.chart-component.views
  (:require [re-frame.core :refer [dispatch subscribe]]))

(defn stacked-barchart [displayed-transactions categories period chart-size]
  [:div
   [:button {:on-click #(dispatch [:toggle-chart-size])} "Vis alle kategorier"]
   [:div
    {:id "mychart" :style {:width "100%" :overflow "visible"}}
    (dispatch [:draw-stacked-barchart displayed-transactions categories period chart-size])]])
