(ns odin.services.category-service 
  (:require [odin.db :as db]
            [clojure.data.json :as json]))

(defn store-categories-handler [request]
  (println request)
  (let [category-updates (:body request)
        categories (map :category category-updates)
        tx-response (db/store-categories categories)
        ;; category-tempids (:tempids tx-response)
        ;; {add-category-updates true
        ;;  remove-category-updates false} (group-by #(-> % :category some?)
        ;;                                           (mapcat :updated-transactions category-updates))
        ;; tx-category-response (db/add-category-to-transactions add-category-updates)
        ;; tx-rm-cat-res (db/remove-category-from-transactions remove-category-updates)
        ]
    (doall (for [category-update category-updates]
             (let [{add-category-updates true
                    remove-category-updates false} (group-by #(-> % :category-id some?)
                                                             (:updated-transactions category-update))]
               (db/add-category-to-transactions add-category-updates)
               (db/remove-category-from-transactions remove-category-updates))))
    (if true
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "{\"message\": \"Stored categories successfully\"}"}
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Failed to store categories"})))

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