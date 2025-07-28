(ns client.components.period-selector-component.events
  (:require [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]
   ))

(defn first-click [period] 
  [period nil])

(defn second-click [period selected-range]
  (let [[start _] selected-range]
    (when (not= start period)
      (dispatch [:navigate [{:start (min (:start start) (:start period))
                             :end   (max (:end start) (:end period))
                             :period-type (:period-type period)} nil nil]])
      [start period])))

(reg-event-db
 :set-selected-range
 (fn
   [db [_ period]]
   (let [selected-range (-> db :period-selector :selected-range)
         _ (println "selected-range" selected-range)
         new-selected-range (if (or (nil? selected-range)
                                    (-> selected-range second some?))
                              (first-click period)
                              (second-click period selected-range))]
     (-> db
         (assoc-in [:period-selector :selected-range] new-selected-range)))))