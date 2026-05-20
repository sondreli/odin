(ns odin.services.loan-service
  (:require [odin.db2 :as db2]
            [clojure.data.json :as json]
            [common.category-service :as category-svc]))

(defn loan-filter-texts
  "Returns the loan's filter-texts vector, falling back to a singleton wrap of
   the legacy :filter-text when needed."
  [loan]
  (or (:filter-texts loan)
      (when (seq (:filter-text loan)) [(:filter-text loan)])
      []))

(defn matching-txns-for-loan
  "Subset of `txns` whose descriptions match any of the loan's filter-texts,
   sorted by date. Returns nil when the loan has no filter-texts."
  [loan txns]
  (let [texts (loan-filter-texts loan)]
    (when (seq texts)
      (vec (->> txns
                (filter (fn [tx]
                          (some #(category-svc/match-fun (:description tx) %) texts)))
                (sort-by :date))))))

(defn store-loan-handler [request]
  (let [user-id (:user-id request)
        loan (:body request)
        loan-with-id (if (contains? loan :id)
                       (assoc loan :user-id user-id)
                       (-> loan
                           (assoc :user-id user-id)
                           (assoc :id (str (random-uuid)))))]
    (println "Storing loan:" (pr-str loan-with-id))
    (try
      (db2/store-loan loan-with-id)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str loan-with-id)}
      (catch Exception e
        (println "Failed to store loan:" (.getMessage e))
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (str "{\"error\": \"Failed to store loan: " (:name loan) "\"}")}))))

(defn loans-handler [request]
  (let [user-id (:user-id request)
        loans (db2/get-loans user-id)]
    (println "Retrieved" (count loans) "loans")
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str loans)}))

(defn delete-loan-handler [loan-id request]
  (let [user-id (:user-id request)]
    (db2/delete-loan user-id loan-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"message\": \"Deleted loan successfully\"}"}))
