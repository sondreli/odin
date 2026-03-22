(ns odin.services.report-service
  (:require [odin.db2 :as db2]
            [clojure.data.json :as json]))

(defn store-report-handler [request]
  (let [user-id (:user-id request)
        report (:body request)
        report-with-id (if (contains? report :id)
                         (assoc report :user-id user-id)
                         (-> report
                             (assoc :user-id user-id)
                             (assoc :id (str (random-uuid)))))]
    (try
      (db2/store-report report-with-id)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str report-with-id)}
      (catch Exception e
        (println "Failed to store report:" (.getMessage e))
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (str "{\"error\": \"Failed to store report: " (:name report) "\"}")}))))

(defn reports-handler [request]
  (let [user-id (:user-id request)
        reports (db2/get-reports user-id)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str reports)}))

(defn delete-report-handler [report-id request]
  (let [user-id (:user-id request)]
    (db2/delete-report user-id report-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"message\": \"Deleted report successfully\"}"}))
