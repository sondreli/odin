(ns odin.services.category-service 
  (:require [odin.db :as db]
            [clojure.data.json :as json]))

(defn store-category-handler [request]
  (println request)
  (let [;category-updates (:body request)
        category (:body request) ;(map :category category-updates)
        stored-category (db/store-categories category)
        ;; category-tempids (:tempids tx-response)
        ;; {add-category-updates true
        ;;  remove-category-updates false} (group-by #(-> % :category some?)
        ;;                                           (mapcat :updated-transactions category-updates))
        ;; tx-category-response (db/add-category-to-transactions add-category-updates)
        ;; tx-rm-cat-res (db/remove-category-from-transactions remove-category-updates)
        ]
    
    (if true
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (json/write-str stored-category)}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Failed to store categories"})))

(defn update-transactions-with-categories [request]
  (let [category-updates (:body request)]
    (doall (for [category-update category-updates]
             (let [{add-category-updates true
                    remove-category-updates false} (group-by #(-> % :category-id some?)
                                                             (:updated-transactions category-update))]
               (db/add-category-to-transactions add-category-updates)
               (db/remove-category-from-transactions remove-category-updates))))
    (if true
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "{\"message\": \"Update transactions with categories successfully\"}"}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Failed to update transactions"})))

(defn categories-handler [request]
  (println request)
  (let [categories (db/get-categories)]
    (if true
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (json/write-str categories)}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Failed to store categories"})))