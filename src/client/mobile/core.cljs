(ns client.mobile.core
  "Entry point for the React Native mobile app. Shares re-frame app state and events with the web app."
  (:require [re-frame.core :as rf :refer [dispatch-sync dispatch]]
            [reagent.core :as reagent]
            [client.db]
            [client.events]
            [client.events.period-selector]
            [client.events.displayed-transactions-viewer]
            [client.subs]
            [client.routes]
            [client.components.chart-component.events]
            [client.components.transactions-table-component.events]
            [client.components.period-selector-component.events]
            [client.components.treemap-component.events]
            [client.components.reports.events]
            [client.mobile.views :as views])
  (:require ["react-native" :refer [AppRegistry]]))

(defn init []
  "Register the Odin mobile root component with React Native. Call this from the app's index.js."
  (dispatch-sync [:initialise-db])
  (dispatch [:request-all-transactions])
  (dispatch [:request-all-categories])
  (dispatch [:request-all-reports])
  (dispatch [:request-all-tags])
  (let [root-component (reagent/reactify-component views/odin-mobile-app)]
    (.registerComponent AppRegistry "Odin" (fn [] root-component)))
  (set! (.-init js/module.exports) init))
