(ns client.core
  (:require [day8.re-frame.http-fx]
            [client.views :as views]
            [client.events :as events]
            [client.components.chart-component.events :as chart-events]
            [client.components.transactions-table-component.events :as t-table-events]
            [client.components.period-selector-component.events :as period-selector-events]
            [client.components.treemap-component.events]
            [client.components.reports.events]
            [client.subs]
            [client.components.treemap-component.subs]
            [client.db]
            [client.routes :as routes]
            [client.api :as api]
            [reagent.dom :as rdom]
            [re-frame.core :as rf :refer [dispatch-sync]]))


(defn mount []
  (rdom/render [client.views/odin-app]
               (.getElementById js/document "app")))

(defn reload! []
  (mount)
  (views/remove-barchart)
  (print "Hello World reloaded!"))

(defn- stored-theme []
  (try
    (let [v (.getItem js/localStorage "odin-theme")]
      (case v
        "dark"  :dark
        "light" :light
        :light))
    (catch :default _ :light)))

(defn main! []
  (dispatch-sync [:initialise-db])
  (rf/dispatch [:set-theme (stored-theme)])
  (rf/dispatch [:check-auth])
  (mount)
  (print "Hello World!"))
