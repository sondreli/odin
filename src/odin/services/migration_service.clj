(ns odin.services.migration-service
  (:require [datomic.client.api :as d]
            ;; [datomic.api :as da]
            [clojure.java.io :as io]
            ;; [odin.db :as db]
            [io.rkn.conformity :as c]))

(def client (d/client {:server-type :datomic-local
                       :system "pengamine"}))

(def conn (d/connect client {:db-name "transactions"}))

(defn load-resource [resource-path]
  (-> resource-path
      io/resource
      slurp
      read-string))

(defn apply-migration [conn resource-path]
  (let [migration (load-resource resource-path)]
    (c/ensure-conforms conn {:txes [(:txes migration)]})))

(defn migrate []
  (doseq [migration ["odin/migrations/2024-07-15__initial_schema.edn"]]
    (apply-migration conn migration)))