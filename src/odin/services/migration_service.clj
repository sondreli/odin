(ns odin.services.migration-service
  (:require [datomic.client.api :as d]
            [clojure.java.io :as io]
            [conformity.core :as c]))

(defn load-resource [resource-path]
  (-> resource-path
      io/resource
      slurp
      read-string))

(defn apply-migration [conn resource-path]
  (let [migration (load-resource resource-path)]
    (c/ensure-conforms conn {:txes [(:txes migration)]})))

(defn migrate [conn]
  (doseq [migration ["migrations/001-add-user.edn"]]
    (apply-migration conn migration)))