(ns odin.services.tag-service
  (:require [odin.db2 :as db2]
            [clojure.data.json :as json]))

(defn store-tag-handler [request]
  (let [user-id (:user-id request)
        tag (:body request)
        tag-with-id (if (contains? tag :id)
                      (assoc tag :user-id user-id)
                      (-> tag
                          (assoc :user-id user-id)
                          (assoc :id (str (random-uuid)))))]
    (try
      (db2/store-tag tag-with-id)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str tag-with-id)}
      (catch Exception e
        (println "Failed to store tag:" (.getMessage e))
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (str "{\"error\": \"Failed to store tag: " (:name tag) "\"}")}))))

(defn tags-handler [request]
  (let [user-id (:user-id request)
        tags (db2/get-tags user-id)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str tags)}))

(defn delete-tag-handler [tag-id request]
  (let [user-id (:user-id request)]
    (db2/delete-tag user-id tag-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"message\": \"Deleted tag successfully\"}"}))
