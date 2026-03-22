(ns odin.services.category-service 
  (:require [odin.db2 :as db2]
            [clojure.data.json :as json]))

(defn store-category-handler [request]
  (let [user-id (:user-id request)
        category (:body request)
        category-with-id (if (contains? category :id)
                           (assoc category :user-id user-id)
                           (-> category
                               (assoc :user-id user-id)
                               (assoc :id (str (random-uuid)))))]
    (try
      (db2/store-category category-with-id)
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (json/write-str category-with-id)}
      (catch Exception e
        (println "Failed to store category: " (.getMessage e) (.getStackTrace e))
        {:status 500
         :headers {"Content-Type" "text/html"}
         :body (str "Failed to store category: " (:name category))}))
    ))

(defn update-transactions-with-categories [request]
  (println "update-transactions-with-categories")
  (let [user-id (:user-id request)
        updated-transactions (:body request)
        transactions-with-user (map #(assoc % :user-id user-id) updated-transactions)]
    (db2/store-transactions transactions-with-user)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"message\": \"Updated transactions with categories successfully\"}"}))

(defn- attach-filters-to-categories [categories filters]
  (let [filters-by-cat (group-by :category-id filters)]
    (mapv (fn [cat]
            (let [cat-filters (get filters-by-cat (:id cat) [])
                  sorted-filters (sort-by :order-index cat-filters)]
              (assoc cat :filters sorted-filters)))
          categories)))

(defn categories-handler [request]
  (let [user-id (:user-id request)
        categories (db2/get-categories user-id)
        filters (db2/get-filters user-id)
        categories-with-filters (attach-filters-to-categories categories filters)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str categories-with-filters)}))

(defn delete-category-handler [category-id request]
  (let [user-id (:user-id request)
        updated-transactions (:body request)]
    (db2/delete-category user-id category-id)
    (db2/delete-filters-by-category user-id category-id)
    (db2/store-transactions updated-transactions)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"message\": \"Deleted category successfully\"}"}))

(defn migrate-markers-to-filters [user-id]
  (let [categories (db2/get-categories user-id)
        existing-filters (db2/get-filters user-id)
        cats-with-filters (set (map :category-id existing-filters))]
    (doseq [cat categories]
      (when (and (not (contains? cats-with-filters (:id cat)))
                 (:marker cat)
                 (seq (get-in cat [:marker :description])))
        (let [lines (get-in cat [:marker :description])
              filters (map-indexed (fn [idx text]
                                     {:user-id user-id
                                      :id (str (random-uuid))
                                      :category-id (:id cat)
                                      :text text
                                      :order-index idx
                                      :tag-ids []})
                                   lines)]
          (println "Migrating" (count filters) "filters for category:" (:name cat))
          (db2/store-filters filters))))
    {:migrated true}))

(defn migrate-handler [request]
  (let [user-id (:user-id request)]
    (try
      (let [result (migrate-markers-to-filters user-id)]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str result)})
      (catch Exception e
        (println "Migration failed:" (.getMessage e))
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body "{\"error\": \"Migration failed\"}"}))))
