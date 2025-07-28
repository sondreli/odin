(ns odin.services.category-service 
  (:require [odin.db :as db]
            [odin.db2 :as db2]
            [clojure.data.json :as json]))

(defn store-category-handler [request]
  (println request)
  (let [;category-updates (:body request)
        category (:body request) ;(map :category category-updates)
        category-with-id (if (contains? category :id)
                           (-> category
                               (assoc :user-id "xxx"))
                           (-> category
                               (assoc :user-id "xxx")
                               (assoc :id (str (random-uuid)))))
        ;stored-category (db/store-categories category)
        ;; _ (db2/store-category category-with-id)
        ;; category-tempids (:tempids tx-response)
        ;; {add-category-updates true
        ;;  remove-category-updates false} (group-by #(-> % :category some?)
        ;;                                           (mapcat :updated-transactions category-updates))
        ;; tx-category-response (db/add-category-to-transactions add-category-updates)
        ;; tx-rm-cat-res (db/remove-category-from-transactions remove-category-updates)
        ]

    (try
      (db2/store-category category-with-id)
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (json/write-str category-with-id)}
      ;; (catch ResourceNotFoundException e
      ;;   {:status 500
      ;;    :headers {"Content-Type" "text/html"}
      ;;    :message (str "Could not find table " category "\n" (.getMessage e))})
      (catch Exception e
        (println "Failed to store category: " (.getMessage e) (.getStackTrace e))
        {:status 500
         :headers {"Content-Type" "text/html"}
         :body (str "Failed to store category: " (:name category))}))
    ))

(defn update-transactions-with-categories [request]
(println "update-transactions-with-categories")
  (let [updated-transactions (:body request)
  ; only update values are included here and not the full transactions
  ; dynamodb only support PutItem and DeleteItem for BatchWriteItem
        ; conclusion: send the full transactions
        {add-category-updates true
         remove-category-updates false} (group-by #(-> % :category-id some?) updated-transactions)
        transactions (map #(assoc % :user-id "xxx") add-category-updates)]
    ;; (db/add-category-to-transactions add-category-updates)
    (db2/store-transactions updated-transactions)
    ;; (db/remove-category-from-transactions remove-category-updates)
    (if true
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body "{\"message\": \"Updated transactions with categories successfully\"}"}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Failed to update transactions"})))

(defn categories-handler [request]
  (println request)
  (let [categories (db2/get-categories)]
    (if true
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (json/write-str categories)}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Failed to store categories"})))

(defn delete-category-handler [category-id request]
  (let [updated-transactions (:body request)]
    (db2/delete-category "xxx" category-id)
    (db2/store-transactions updated-transactions)
    (if true
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "{\"message\": \"Deleted category successfully\"}"}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Failed to store categories"})))