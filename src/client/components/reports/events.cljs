(ns client.components.reports.events
  (:require [re-frame.core :refer [reg-event-db reg-event-fx]]
            [ajax.core :as ajax]
            [client.api :as api]))

(reg-event-fx
 :request-all-reports
 (fn [_ _]
   {:http-xhrio {:method          :get
                 :uri             (api/uri "/reports")
                 :headers         (api/auth-header)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:get-reports-response]
                 :on-failure      [:get-reports-failure]}}))

(reg-event-db
 :get-reports-response
 (fn [db [_ response]]
   (let [reports (js->clj response)]
     (assoc db :reports reports))))

(reg-event-db
 :get-reports-failure
 (fn [db [_ error]]
   (println "Failed to fetch reports:" error)
   db))

(reg-event-db
 :set-reports-period
 (fn [db [_ period]]
   (assoc db :reports-period period)))

(reg-event-fx
 :store-report
 (fn [{db :db} [_ report]]
   (let [rp (:reports-period db)
         report-with-period (merge report
                                   {:period-start (-> rp :start .getTime (/ 1000))
                                    :period-end   (-> rp :end .getTime (/ 1000))
                                    :period-type  (-> rp :period-type name)})]
     {:http-xhrio {:method          :post
                   :uri             (api/uri "/report")
                   :params          report-with-period
                   :headers         (api/auth-header)
                   :format          (ajax/json-request-format)
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:store-report-response]
                   :on-failure      [:store-report-failure]}})))

(reg-event-fx
 :store-report-response
 (fn [_ [_ _response]]
   (println "Report stored successfully")
   {:dispatch [:request-all-reports]}))

(reg-event-db
 :store-report-failure
 (fn [db [_ error]]
   (println "Failed to store report:" error)
   db))

(reg-event-fx
 :delete-report
 (fn [_ [_ report-id]]
   {:http-xhrio {:method          :delete
                 :uri             (api/uri-with-id "/report/" report-id)
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:delete-report-response]
                 :on-failure      [:delete-report-failure]}}))

(reg-event-fx
 :delete-report-response
 (fn [_ [_ _response]]
   (println "Report deleted successfully")
   {:dispatch [:request-all-reports]}))

(reg-event-db
 :delete-report-failure
 (fn [db [_ error]]
   (println "Failed to delete report:" error)
   db))
