(ns odin.db
  (:require [datomic.client.api :as d]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [clj-fuzzy.metrics :as fuzzy]
            [odin.db-schemas :as schemas]))

(def client (d/client {:server-type :datomic-local
                       :system "pengamine"}))

(d/list-databases client {})

(def conn (d/connect client {:db-name "transactions"}))

;; (def conn (d/connect client {:db-name "test"}))

;; (ns-unmap 'odin.db 'conn)

(defn create-db [db-name]
  (let [client (d/client {:server-type :datomic-local
                          :system "pengamine"})
        _ (d/create-database client {:db-name db-name})
        conn (d/connect client {:db-name db-name})]
    (d/transact conn {:tx-data schemas/transaction-schema})
    (d/transact conn {:tx-data schemas/category-schema})))

;; (create-db "transactions")

;; create transactions db
;; (d/create-database client {:db-name "transactions"})

;; create schemas
;; (d/transact conn {:tx-data schemas/transaction-schema})

;; (d/transact conn {:tx-data schemas/category-schema})

(def test-transactions [{:db/id "myid"
                         :transaction/amount "123.23"
                         :transaction/date 123
                         :transaction/description "Test transaction"
                         :transaction/source "{:lotsof \"info\"}"}])

(def test-transactions2 [[:db/add 83562883712925 :transaction/date 321]])

;; (d/transact conn {:tx-data test-transactions2})

;; (def db (d/db conn))
;; (d/q '[:find ?e ?amount ?description ?date
;;        :where [?e :transaction/amount ?amount]
;;        [?e :transaction/description ?description]
;;        [?e :transaction/date ?date]
;;        [?e :transaction/amount "123.23"]]
;;      (d/db conn))

;; (d/q '[:find ?e ?name ?marker
;;        :where [?e :category/name ?name] 
;;               [?e :category/marker ?marker]]
;;      (d/db conn))

;; (d/q '[:find ?e ?description ?amount ?date
;;        :in $ ?e
;;        :where [?e :transaction/description ?description]
;;        [?e :transaction/amount ?amount]
;;        [?e :transaction/date ?date]
;;        ;[?e :transaction/category ?category]
;;        ;[?e :transaction/db-id ?db-id]
;;        ]
;;      (d/db conn) 96757023246267)

;; (d/q '[:find ?e ?description ?amount ?date
;;        :in $ ?date
;;        :where [?e :transaction/description ?description]
;;        [?e :transaction/amount ?amount]
;;        [?e :transaction/date ?date]
;;        ;[?e :transaction/category ?category]
;;        ;[?e :transaction/db-id ?db-id]
;;        ]
;;     ;;  (d/db conn) "-4099.0")
;;      (d/db conn) 1706742000000)


(defn assoc-attribute [mymap [attr value]]
  (if (nil? value)
    mymap
    (assoc mymap attr value)))

(defn pr-edn-str [& xs]
  (binding [*print-length* nil
            *print-dup* nil
            *print-level* nil
            *print-readably* true]
    (apply pr-str xs)))

(defn transaction->db-entry [transaction]
  (let [data {:transaction/amount (-> transaction :amount str)
              :transaction/description (:description transaction)
              :transaction/date (:date transaction)
              :transaction/source (pr-edn-str transaction)}]
    (reduce assoc-attribute {} data)))

(defn transaction->db-entry2 [transaction]
  (let [data {:db/id (:temp-id transaction)
              :transaction/amount (-> transaction :amount str)
              :transaction/description (:description transaction)
              :transaction/date (:date transaction)
              :transaction/source (-> transaction :source pr-edn-str)
              :transaction/category-id (:category-id transaction)}]
    (reduce assoc-attribute {} data)))

(defn category->db-entry [category]
  (let [data {:db/id (:temp-id category)
              :category/id (-> category :id parse-uuid)
              :category/name (:name category)
              :category/color (:color category)
              :category/color-value (:color-value category)
              :category/marker (-> category :marker pr-edn-str)}]
    (reduce assoc-attribute {} data)))

;; (to-db-entry {:amount 10
;;               :description "test"
;;               :date nil
;;               :source {:hello "world"}})

(defn store-transactions [transactions]
  (println "storing transactions")
  (println (count transactions))
  (let [data (->> transactions
                  (filter #(-> % :source (not= "RECENT"))) ; this will be removed when replacements are working
                  (map transaction->db-entry))]
    ;;   (println data)
    (d/transact conn {:tx-data data})))

(defn add-db-id [temp-ids transaction]
  (let [db-id (->> transaction :temp-id (get temp-ids))]
    (-> transaction
        (assoc :db-id db-id)
        (dissoc :temp-id))))

(defn store-transactions2 [transactions]
  (let [data (map transaction->db-entry2 transactions)
        tx-response (d/transact conn {:tx-data data})
        temp-ids (:tempids tx-response)
        stored-transactions (map #(add-db-id temp-ids %) transactions)]
    stored-transactions))

;; (store-transactions2
;;  [{:amount 2.0 :date 1703631600000 :temp-id "1" :description "cat"}
;;   {:amount 3.0 :date 1703631600000 :temp-id "2" :description "asdg" :category {:color "#aff"}}
;;   { 3.0 :date 1703631600000 :temp-id "3" :description "asdf"}])

(defn replace-transaction [transaction]
  (let [data {:db/id (:db-id transaction)
              :transaction/date (:date transaction)
              :transaction/description (:description transaction)
              :transaction/source (-> transaction :source pr-edn-str)}]
    ;; data
    (if (contains? transaction :category-id)
      (assoc data :transaction/category-id (:category-id transaction))
      data)
    ))

(defn replace-transactions [transactions]
  (let [data (->> transactions
                  (map replace-transaction)
                  (into []))
        _ (println data)
        tx-response (d/transact conn {:tx-data data})]
    (println tx-response)
    data))

(defn add-if-some [map key val]
  (if (and (some? val) val)
    (assoc map key val)
    map))

(defn add-update-if-some [map key fun val]
  (if (and (some? val) val)
    (assoc map key (fun val))
    map))

;; (let [a "{:name Mat :color #afa}"
;;       b (pr-str a)
;;       ;c (s/split b #"")
;;       c (read-string a)
;;       ;c (add-update-if-some {} :category read-string b)
;;       ]
;;   c)


(defn get-transactions []
  (println "get-transactions")
  (let [db-result (->> (d/q '[:find ?e ?date ?amount ?description ?source ?category-id
                              :where [?e :transaction/amount ?amount]
                              [?e :transaction/description ?description]
                              [?e :transaction/date ?date]
                              [?e :transaction/source ?source]
                              [(get-else $ ?e :transaction/category-id false) ?category-id]]
                            (d/db conn))
                       (sort-by second))
        to-transaction-map (fn [[eid date amount desc source category-id]]
                             (-> {:db-id eid
                                  :amount (Double/parseDouble amount)
                                  :description desc
                                  :date date
                                  :source (edn/read-string source)}
                                ;;  (add-update-if-some :category edn/read-string category)
                                 (add-if-some :category-id category-id)))]
    (println "get-transactions-from-db: " (count db-result))
    (->> db-result (map to-transaction-map) (sort-by :date))))

;; (d/q '[:find ?e ?description ?category
;;        :where [?e :transaction/description ?description]
;;        [?e :transaction/category ?category]] (d/db conn))

(defn store-categories [categories]
  (let [data (->> categories
                  (map category->db-entry))]
    (d/transact conn {:tx-data data})))

(defn add-category [transaction]
  (let [eid (:db-id transaction)
        category-id (:category-id transaction)]
    {:db/id eid :transaction/category-id [:category/id category-id]}))

(defn add-category-to-transactions [transactions]
  (println "add-category-to-transactions" (count transactions))
  (let [all-updates (map add-category transactions)]
    (println all-updates)
    (d/transact conn {:tx-data all-updates})))

(defn remove-category-from-transactions [transaction-updates]
  (println "remove-category-from-transactions" (count transaction-updates))
  (let [data (map #(vector :db/retract
                           (:db-id %)
                           :transaction/category-id (-> % :old-category-id pr-edn-str)) transaction-updates)]
    (println data)
    (d/transact conn {:tx-data data})))

(defn get-categories []
  (println "get-categories")
  (let [db-result (->> (d/q '[:find ?e ?id ?name ?color ?color-value ?marker
                              :where
                              [?e :category/id ?id]
                              [?e :category/name ?name]
                              [?e :category/color ?color]
                              [?e :category/color-value ?color-value]
                              [?e :category/marker ?marker]]
                            (d/db conn))
                       (sort-by first))
        to-transaction-map (fn [[eid id name color color-value marker]]
                             (-> {:db-id eid
                                  :id id
                                  :name name
                                  :color color
                                  :color-value color-value
                                  :marker (edn/read-string marker)}))]
    (println "get-categories-from-db: " (count db-result))
    (map to-transaction-map db-result)))

