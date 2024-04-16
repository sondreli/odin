(ns client.events.period-selector
  (:require [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]
            [client.events.utils :as utils]
            ))

;; (reg-event-db
;;  :set-period-length
;;  [utils/check-spec-interceptor]
;;  (fn [db [_ period-length]]
;;    (let [updated-period-selector (-> db :period-selector (assoc :length period-length))
;;          _ (println period-length)
;;          period (utils/period updated-period-selector)
;;          updated-db (utils/apply-period db updated-period-selector period)]
;;      (println period)
;;      updated-db)))

(reg-event-fx
 :set-period-length
 (fn
   [{db :db
     [_ period-length] :event} _]
   (let [updated-period-selector (-> db :period-selector (assoc :length period-length))
         _ (println period-length)
         period (utils/period updated-period-selector)]
     (dispatch [:navigate [period nil nil]])
     {:db (assoc db :period-selector updated-period-selector)})))

(reg-event-fx
 :set-period-year
 (fn
   [{db :db
     [_ period-year] :event} _]
   (let [updated-period-selector (-> db :period-selector (assoc-in [:a-month :year] period-year))
         period (utils/period updated-period-selector)]
     (dispatch [:navigate [period nil nil]])
     {:db (assoc db :period-selector updated-period-selector)})))

(reg-event-fx
 :set-period-month
 (fn
   [{db :db
     [_ month-index] :event} _]
   (let [updated-period-selector (-> db
                                     :period-selector
                                     (assoc-in [:a-month :month-index] month-index))
         period (utils/period updated-period-selector)]
     (dispatch [:navigate [period nil nil]])
     {:db (assoc db :period-selector updated-period-selector)})))
