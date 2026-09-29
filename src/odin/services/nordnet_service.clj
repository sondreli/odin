(ns odin.services.nordnet-service
  "Parse Nordnet transaction CSV exports into investment transactions.

   The export is a UTF-16 LE, tab-separated file with Norwegian-formatted numbers
   (space/NBSP thousands separators, comma decimal separator). The client decodes
   the file to a UTF-8 string before POSTing, so here we receive a plain string."
  (:require [clojure.string :as s]
            [clojure.data.json :as json]
            [odin.db2 :as db2]
            [odin.services.date-service :as date]))

(defn parse-num
  "Parse a Norwegian-formatted number string to a double, or nil if blank."
  [v]
  (when (and v (not (s/blank? v)))
    (-> v
        (s/replace #"[\s ]" "")
        (s/replace "," ".")
        Double/parseDouble)))

(def ^:private type-map
  {"KJØPT" :buy
   "SALG"  :sell})

(defn- split-line [line]
  (mapv s/trim (s/split line #"\t" -1)))

(defn- strip-bom [s]
  (s/replace s #"^﻿" ""))

(defn parse-csv
  "Parse the CSV text into a seq of row maps. Trade rows (KJØPT/SALG) get
   :type \"buy\"/\"sell\" with :isin/:quantity/:price/etc. Every other row becomes a
   :type \"cash\" row carrying its signed :amount-nok (NOK) — used to reconstruct the
   account cash/credit balance. All rows share :source-id :date :date-unix :amount-nok."
  [csv-text]
  (let [lines (->> (s/split-lines (strip-bom csv-text))
                   (remove s/blank?))
        header (split-line (first lines))
        idx (zipmap header (range))
        cell (fn [cells col] (when-let [i (idx col)] (get cells i)))]
    (->> (rest lines)
         (map split-line)
         (keep (fn [cells]
                 (let [ttype     (s/upper-case (or (cell cells "Transaksjonstype") ""))
                       iso-date  (cell cells "Bokføringsdag")
                       source-id (cell cells "Id")
                       trade     (type-map ttype)
                       base {:source-id   source-id
                             :date        iso-date
                             :date-unix   (when-not (s/blank? iso-date)
                                            (-> iso-date date/iso-date-str->date date/date->unixtime))
                             :amount-nok  (parse-num (cell cells "Beløp"))}]
                   (cond
                     (or (s/blank? source-id) (s/blank? iso-date)) nil
                     trade
                     (assoc base
                            :type           (name trade)
                            :isin           (cell cells "ISIN")
                            :security-name  (cell cells "Verdipapir")
                            :quantity       (parse-num (cell cells "Antall"))
                            :total-quantity (parse-num (cell cells "Totalt antall"))
                            :price          (parse-num (cell cells "Kurs"))
                            :fx-rate        (parse-num (cell cells "Vekslingskurs")))
                     :else
                     (assoc base :type "cash" :security-name ttype))))))))

(defn parse-credit-csv
  "Parse a Nordnet *credit account* (kredittkonto) export. Every row becomes a
   :type \"credit\" row carrying the running balance :balance (Saldo) and the
   movement :amount-nok (Beløp). The :balance is what the wealth chart uses as
   the actual leverage held — read straight from Nordnet's reported Saldo."
  [csv-text]
  (let [lines (->> (s/split-lines (strip-bom csv-text))
                   (remove s/blank?))
        header (split-line (first lines))
        idx (zipmap header (range))
        cell (fn [cells col] (when-let [i (idx col)] (get cells i)))]
    (->> (rest lines)
         (map split-line)
         (keep (fn [cells]
                 (let [iso-date  (cell cells "Bokføringsdag")
                       source-id (cell cells "Id")
                       ttype     (s/upper-case (or (cell cells "Transaksjonstype") ""))]
                   (when-not (or (s/blank? source-id) (s/blank? iso-date))
                     {:source-id     source-id
                      :date          iso-date
                      :date-unix     (-> iso-date date/iso-date-str->date date/date->unixtime)
                      :amount-nok    (parse-num (cell cells "Beløp"))
                      :balance       (parse-num (cell cells "Saldo"))
                      :type          "credit"
                      :security-name ttype})))))))

(defn- assign-date-indexes
  "Assign a unique :date-index to each row, continuing past indexes already used
   on that date in the DB so composite sort keys don't collide."
  [rows existing-txns]
  (let [start-counts (->> existing-txns
                          (group-by :date)
                          (reduce-kv (fn [m d txns] (assoc m d (count txns))) {}))]
    (loop [[r & more] (sort-by :date-unix rows)
           counts start-counts
           acc []]
      (if (nil? r)
        acc
        (let [d (:date-unix r)
              i (get counts d 0)]
          (recur more
                 (assoc counts d (inc i))
                 (conj acc (assoc r :date-index i))))))))

(defn- ->record [user-id account-id r]
  {:user-id        user-id
   :account-id     account-id
   :date           (:date-unix r)
   :date-index     (:date-index r)
   :type           (:type r)
   :isin           (:isin r)
   :security-name  (:security-name r)
   :quantity       (:quantity r)
   :total-quantity (:total-quantity r)
   :price          (:price r)
   :amount-nok     (:amount-nok r)
   :fx-rate        (:fx-rate r)
   :source-id      (:source-id r)
   :balance        (:balance r)})

(defn- store-parsed
  "Upsert already-parsed rows for an account, keyed by Nordnet row Id. Rows already
   present are overwritten in place (reusing their date-index) so re-importing the
   same/updated export repairs the stored data. Returns {:imported n :updated n}."
  [user-id account-id parsed]
  (let [parsed (->> parsed
                    (group-by :source-id)            ;; dedupe within the file
                    vals
                    (map first))
        existing-txns (db2/get-investment-transactions user-id)
        existing-by-src (into {} (map (juxt :source-id identity) existing-txns))
        {updates true new-rows false} (group-by #(contains? existing-by-src (:source-id %)) parsed)
        ;; overwrite existing items in place: reuse their date-index so the sort key matches
        updated-records (map (fn [r]
                               (->record user-id account-id
                                         (assoc r :date-index (:date-index (existing-by-src (:source-id r))))))
                             updates)
        ;; new items: assign fresh date-indexes continuing past what's already stored
        new-records (map (partial ->record user-id account-id)
                         (assign-date-indexes (vec new-rows) existing-txns))
        to-store (concat updated-records new-records)]
    (when (seq to-store)
      (db2/store-investment-transactions to-store))
    {:imported (count new-records)
     :updated  (count updated-records)}))

(defn import-transactions
  "Parse a securities-account CSV and upsert its rows for the given account."
  [user-id account-id csv-text]
  (store-parsed user-id account-id (parse-csv csv-text)))

(defn import-credit-transactions
  "Parse a credit-account (kredittkonto) CSV and upsert its rows for the given account."
  [user-id account-id csv-text]
  (store-parsed user-id account-id (parse-credit-csv csv-text)))

(defn- csv-import-response [import-fn user-id account-id csv-text]
  (if (s/blank? csv-text)
    {:status 400
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:error "Missing csv body"})}
    (try
      (let [result (import-fn user-id account-id csv-text)]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str result)})
      (catch Exception e
        (println "CSV import error:" (.getMessage e))
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error (str "CSV import failed: " (.getMessage e))})}))))

(defn import-csv-handler [account-id request]
  (csv-import-response import-transactions (:user-id request) account-id
                       (get-in request [:body :csv])))

(defn import-credit-csv-handler [account-id request]
  (csv-import-response import-credit-transactions (:user-id request) account-id
                       (get-in request [:body :csv])))
