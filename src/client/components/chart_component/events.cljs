(ns client.components.chart-component.events
  (:require [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]
            [client.services.chart-service :as chart]
   ))

(reg-event-fx
 :draw-stacked-barchart
 (fn
   [{db :db
     [_ displayed-transactions categories period chart-size] :event} _]
     (chart/draw-stacked-barchart displayed-transactions categories period chart-size)
   {:db db}))

(reg-event-db
 :toggle-chart-size
 (fn
   [db [_]]
   (println "toggle-chart-size" )
   (let [chart-size-old (-> db :displayed-transactions-data :chart-size)
         chart-size (if (nil? chart-size-old) :full nil)]
     (-> db
         (assoc-in [:displayed-transactions-data :chart-size] chart-size)))))