(ns odin.services.filter-service
  (:require [odin.db2 :as db2]
            [clojure.data.json :as json]))

(defn store-filter-handler [request]
  (let [user-id (:user-id request)
        filter-item (:body request)
        filter-with-id (if (contains? filter-item :id)
                         (assoc filter-item :user-id user-id)
                         (-> filter-item
                             (assoc :user-id user-id)
                             (assoc :id (str (random-uuid)))))]
    (try
      (db2/store-filter filter-with-id)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str filter-with-id)}
      (catch Exception e
        (println "Failed to store filter:" (.getMessage e))
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (str "{\"error\": \"Failed to store filter\"}")}))))

(defn store-filters-handler [request]
  (let [user-id (:user-id request)
        filters (:body request)
        category-id (:category-id (first filters))
        filters-with-ids (mapv (fn [f]
                                 (if (contains? f :id)
                                   (assoc f :user-id user-id)
                                   (-> f
                                       (assoc :user-id user-id)
                                       (assoc :id (str (random-uuid))))))
                               filters)]
    (try
      (when category-id
        (db2/delete-filters-by-category user-id category-id))
      (db2/store-filters filters-with-ids)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str filters-with-ids)}
      (catch Exception e
        (println "Failed to store filters:" (.getMessage e))
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body "{\"error\": \"Failed to store filters\"}"}))))

(defn filters-handler [request]
  (let [user-id (:user-id request)
        filters (db2/get-filters user-id)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str filters)}))

(defn delete-filter-handler [filter-id request]
  (let [user-id (:user-id request)]
    (db2/delete-filter user-id filter-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"message\": \"Deleted filter successfully\"}"}))
