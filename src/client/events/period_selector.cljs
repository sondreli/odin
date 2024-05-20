(ns client.events.period-selector
  (:require [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]
            [client.events.utils :as utils]
            
            [client.services.date-service :as date]))

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
 :set-period-year
 (fn
   [{db :db
     [_ new-year] :event} _]
   (let [current-period (-> db :period)
         new-period (date/set-period-to-year current-period new-year)]
     (dispatch [:navigate [new-period nil nil]])
     {:db db})))

(defn new-year-period [db]
  (let [new-year (date/year-of-timestamp (-> db :period :start))
        new-period (date/period-from-year new-year)]
    new-period))

(defn new-month-period [db]
  (let [new-month-index (date/month-of-timestamp (-> db :period :start))
        year (date/year-of-timestamp (-> db :period :start))
        new-period (date/month-period new-month-index year)]
    new-period))

(reg-event-fx
 :set-time-unit
 (fn
   [{db :db
    [_ time-unit] :event} _]
   (let [new-period (if (= time-unit :year)
                      (new-year-period db)
                      (new-month-period db))]
     (dispatch [:navigate [new-period nil nil]])
     {:db db})))