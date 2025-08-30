(ns client.components.transactions-table-component.events
  (:require [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]))

(reg-event-db
 :sort-column
 (fn
   [db [_ column]]
   (println "sort-column: " column)
   (let [new-sort-order (if (= :reverse (-> db :displayed-transactions-data :sort-order))
                          :straight
                          :reverse)
         new-displayed-transactions (if (= new-sort-order :reverse)
                                      (->> db :displayed-transactions-data :displayed-transactions
                                           (sort-by column)
                                           reverse
                                           (into []))
                                      (->> db :displayed-transactions-data :displayed-transactions
                                           (sort-by column)
                                           (into [])))
         ]
     (-> db
         (assoc-in [:displayed-transactions-data :sort-column] column)
         (assoc-in [:displayed-transactions-data :sort-order] new-sort-order)
         (assoc-in [:displayed-transactions-data :displayed-transactions] new-displayed-transactions)))))