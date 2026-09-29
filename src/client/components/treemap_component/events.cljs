(ns client.components.treemap-component.events
  (:require [re-frame.core :refer [reg-event-db]]))

(reg-event-db
 :set-treemap-target
 (fn [db [_ value]]
   (assoc db :treemap-target
          (let [s (when (some? value) (str value))]
            (when (and s (not= s "")) s)))))
