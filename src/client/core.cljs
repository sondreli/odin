(ns client.core
  (:require [day8.re-frame.http-fx]
            [client.views :as views]
            [client.events :as events]
            [client.subs]
            [client.db]
            [client.routes :as routes]
            ;; [client.date-service]
            [reagent.core :as reagent]
            [re-frame.core :as rf :refer [dispatch-sync]]))


(defn mount []
  (reagent/render-component [client.views/odin-app]
                       (.getElementById js/document "app")))

(defn reload! []
  (mount)
  (views/remove-barchart)
  (print "Hello World reloaded!"))

(defn main! []
  (dispatch-sync [:initialise-db])
  (mount)
  (print "Hello World!"))
