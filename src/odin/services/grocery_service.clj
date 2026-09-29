(ns odin.services.grocery-service
  "Fetch grocery receipt line items from Norwegian loyalty apps (Trumf, Rema Æ, Coop).

   Uses reverse-engineered private APIs. Credentials stored on Account rows:
   :client-secret holds the Bearer access token (pasted from browser/app session).
   :client-id may hold phone/username for display only.

   ⚠️ Unofficial. Endpoints can change when apps update. Personal use only."
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [odin.db2 :as db2]
            [odin.services.account-service :as account-svc]
            [odin.services.coop-auth :as coop-auth]
            [odin.services.date-service :as date]
            [odin.services.http-service :as http])
  (:import [java.time Instant LocalDate LocalDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

(def ^:private rema-subscription-key "fb5e24884b504d0bad761098f77e6605")

(def ^:private trumf-base "https://platform-rest-prod.ngdata.no/trumf")
(def ^:private rema-base "https://api.rema.no/v1/bella")
(def ^:private coop-base "https://api.coop.no")

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- strip-bearer [token]
  (let [t (s/trim (str token))]
    (if (s/starts-with? (s/lower-case t) "bearer ")
      (s/trim (subs t 7))
      t)))

(defn- auth-headers
  ([token] (auth-headers token nil))
  ([token extra]
   (merge {"Authorization" (str "Bearer " (strip-bearer token))
           "Accept" "application/json"
           "User-Agent" "Odin/1.0 (personal grocery sync)"}
          extra)))

(defn- parse-json [body]
  (when (and body (not (s/blank? body)))
    (json/read-str body :key-fn keyword)))

(defn- ->double [v]
  (cond
    (nil? v) nil
    (number? v) (double v)
    (string? v)
    (try
      (-> v
          (s/replace #"[\s ]" "")
          (s/replace "," ".")
          Double/parseDouble)
      (catch Exception _ nil))
    :else nil))

(defn- iso-date->ms
  "Parse YYYY-MM-DD or ISO datetime to epoch ms (Europe/Oslo midnight for dates)."
  [s]
  (when (and s (not (s/blank? (str s))))
    (try
      (let [str-s (str s)]
        (if (re-find #"^\d{4}-\d{2}-\d{2}$" str-s)
          (-> str-s date/iso-date-str->date date/date->unixtime)
          ;; Instant / OffsetDateTime strings
          (try
            (.toEpochMilli (Instant/parse str-s))
            (catch Exception _
              (try
                (let [ldt (LocalDateTime/parse str-s DateTimeFormatter/ISO_DATE_TIME)
                      zdt (.atZone ldt (ZoneId/of "Europe/Oslo"))]
                  (.toEpochMilli (.toInstant zdt)))
                (catch Exception _
                  (let [ld (LocalDate/parse (subs str-s 0 10))]
                    (-> (str ld) date/iso-date-str->date date/date->unixtime))))))))
      (catch Exception e
        (println "iso-date->ms failed for" s ":" (.getMessage e))
        nil))))

(defn- ms-from-unix-or-ms [n]
  (when n
    (let [n (long n)]
      ;; Rema uses ms; if value looks like seconds, scale up
      (if (< n 100000000000) (* n 1000) n))))

(defn- assign-date-indexes
  "Assign unique :date-index per date, continuing past indexes already used in DB."
  [rows existing]
  (let [start-counts (->> existing
                          (group-by :date)
                          (reduce-kv (fn [m d txns] (assoc m d (count txns))) {}))]
    (loop [[r & more] (sort-by (juxt :date :source-id) rows)
           counts start-counts
           acc []]
      (if (nil? r)
        acc
        (let [d (:date r)
              i (get counts d 0)]
          (recur more
                 (assoc counts d (inc i))
                 (conj acc (assoc r :date-index i))))))))

;; ---------------------------------------------------------------------------
;; Trumf (NorgesGruppen: Kiwi, Meny, Spar, Joker, …)
;; ---------------------------------------------------------------------------

(defn- fetch-trumf-items
  "List transactions then expand line items. Token = browser Authorization Bearer."
  [token]
  (let [headers (auth-headers token {"Content-type" "application/json"})
        ;; Last ~18 months by default
        til (.toString (LocalDate/now))
        fra (.toString (.minusMonths (LocalDate/now) 18))
        list-resp (http/http-get (str trumf-base "/husstand/transaksjoner")
                                 {:headers headers
                                  :query-params {"felter" "dato,beskrivelse,kjedeid,partnerid,batchid,belop,trumf,ekstratrumf,trumfvisa,literbensin,trumftotal"
                                                 "fra" fra
                                                 "til" til
                                                 "format" "crm"}})
        _ (when (>= (:status list-resp) 400)
            (throw (ex-info (str "Trumf list failed: HTTP " (:status list-resp))
                            {:status (:status list-resp) :body (:body list-resp)})))
        transactions (or (parse-json (:body list-resp)) [])]
    (vec
     (mapcat
      (fn [trans]
        (let [batchid (str (or (:batchid trans) ""))
              date-ms (iso-date->ms (:dato trans))
              store (or (:beskrivelse trans) "Trumf")
              det-resp (http/http-get (str trumf-base "/husstand/transaksjoner/detaljer/" batchid)
                                      {:headers headers})
              details (when (< (:status det-resp) 400)
                        (parse-json (:body det-resp)))
              lines (or (:varelinjer details)
                        [{:vareTekst "Ukjent"
                          :ean "*"
                          :antall "1"
                          :belop (str (:belop trans))}])]
          (map-indexed
           (fn [idx line]
             {:name (or (:vareTekst line) "Ukjent")
              :amount (->double (:belop line))
              :ean (some-> (:ean line) str)
              :quantity (->double (:antall line))
              :store store
              :provider "trumf"
              :receipt-id batchid
              :source-id (str "trumf:" batchid ":" idx ":" (or (:ean line) ""))
              :date date-ms})
           lines)))
      transactions))))

;; ---------------------------------------------------------------------------
;; Rema 1000 (Æ app)
;; ---------------------------------------------------------------------------

(defn- fetch-rema-items
  [token]
  (let [headers (auth-headers token
                              {"ocp-apim-subscription-key" rema-subscription-key})
        list-resp (http/http-get (str rema-base "/transaction/v2/heads")
                                 {:headers headers})
        _ (when (>= (:status list-resp) 400)
            (throw (ex-info (str "Rema list failed: HTTP " (:status list-resp))
                            {:status (:status list-resp) :body (:body list-resp)})))
        body (parse-json (:body list-resp))
        transactions (or (:transactions body) [])]
    (vec
     (mapcat
      (fn [trans]
        (let [tid (str (or (:id trans) ""))
              date-ms (or (ms-from-unix-or-ms (:purchaseDate trans))
                          (iso-date->ms (:purchaseDate trans)))
              store (or (:storeName trans) "Rema 1000")
              rows-resp (http/http-get (str rema-base "/transaction/v2/rows/" tid)
                                       {:headers headers})
              lines (when (< (:status rows-resp) 400)
                      (or (parse-json (:body rows-resp)) []))]
          (map-indexed
           (fn [idx line]
             {:name (or (:prodtxt1 line) (:productName line) "Ukjent")
              :amount (or (->double (:amount line)) (->double (:unitPrice line)))
              :ean (some-> (or (:prodtxt3 line) (:ean line) (:ean13 line)) str)
              :quantity (or (->double (:quantity line)) 1.0)
              :store store
              :provider "rema"
              :receipt-id tid
              :source-id (str "rema:" tid ":" idx ":" (or (:prodtxt3 line) ""))
              :date date-ms})
           (or lines []))))
      transactions))))

;; ---------------------------------------------------------------------------
;; Coop Medlem
;; ---------------------------------------------------------------------------

(defn- fetch-coop-items
  [token]
  (let [headers (auth-headers token)
        list-resp (http/http-get (str coop-base "/user/pay/history/list")
                                 {:headers headers})
        _ (when (>= (:status list-resp) 400)
            (throw (ex-info (str "Coop list failed: HTTP " (:status list-resp))
                            {:status (:status list-resp) :body (:body list-resp)})))
        body (parse-json (:body list-resp))
        ;; Spec shows :purchases; some builds may nest differently
        purchases (or (:purchases body)
                      (mapcat :purchases (:months body))
                      (when (sequential? body) body)
                      [])]
    (vec
     (mapcat
      (fn [stub]
        (let [sid (str (or (:summaryId stub) (:receiptId stub) ""))
              date-ms (iso-date->ms (or (:purchaseDate stub) (:date stub)))
              store (or (:storeName stub) "Coop")
              det-resp (http/http-get (str coop-base "/user/pay/history/details")
                                      {:headers headers
                                       :query-params {"summaryId" sid
                                                      "receiptid" sid}})
              details (when (< (:status det-resp) 400)
                        (parse-json (:body det-resp)))
              summary (or (:summary details) details)
              lines (or (:lines summary) [])]
          (if (seq lines)
            (map-indexed
             (fn [idx line]
               {:name (or (:productName line) "Ukjent")
                :amount (->double (:amount line))
                :ean (some-> (or (:ean13 line) (:gtin13 line) (:barcode line)) str)
                :quantity (or (->double (:quantity line)) 1.0)
                :store (or (:storeName summary) store)
                :provider "coop"
                :receipt-id sid
                :source-id (str "coop:" sid ":" idx ":" (or (:ean13 line) ""))
                :date (or (iso-date->ms (:purchaseDate summary)) date-ms)})
             lines)
            ;; Fallback: one row for the whole receipt if details unavailable
            [{:name (str "Kjøp " store)
              :amount (->double (:amount stub))
              :ean nil
              :quantity 1.0
              :store store
              :provider "coop"
              :receipt-id sid
              :source-id (str "coop:" sid ":total")
              :date date-ms}])))
      purchases))))

;; ---------------------------------------------------------------------------
;; Sync + store
;; ---------------------------------------------------------------------------

(defn- fetch-for-provider [provider token]
  (case provider
    "trumf" (fetch-trumf-items token)
    "rema"  (fetch-rema-items token)
    "coop"  (fetch-coop-items token)
    (throw (ex-info (str "Unknown grocery provider: " provider) {:provider provider}))))

(defn- ->record [user-id account-id r]
  {:user-id    user-id
   :account-id account-id
   :date       (:date r)
   :date-index (:date-index r)
   :name       (:name r)
   :amount     (:amount r)
   :ean        (:ean r)
   :quantity   (:quantity r)
   :store      (:store r)
   :provider   (:provider r)
   :receipt-id (:receipt-id r)
   :source-id  (:source-id r)})

(defn sync-account!
  "Fetch receipt lines for one grocery account and upsert into GroceryItem.
   Returns {:imported n :updated n :total n} or throws."
  [user-id account]
  (let [provider (:provider account)
        token (if (= "coop" provider)
                (coop-auth/access-token user-id account)
                (:client-secret account))
        _ (when (s/blank? token)
            (throw (ex-info "Mangler tilgangstoken for dagligvarekonto" {:account-id (:account-id account)})))
        raw (->> (fetch-for-provider provider token)
                 (filter :date)
                 (filter :source-id)
                 (group-by :source-id)
                 vals
                 (map first)
                 vec)
        existing (db2/get-grocery-items user-id)
        existing-by-src (into {} (map (juxt :source-id identity) existing))
        {updates true new-rows false}
        (group-by #(contains? existing-by-src (:source-id %)) raw)
        updated-records (map (fn [r]
                               (->record user-id (:account-id account)
                                         (assoc r :date-index
                                                (:date-index (existing-by-src (:source-id r))))))
                             updates)
        new-records (map (partial ->record user-id (:account-id account))
                         (assign-date-indexes (vec new-rows) existing))
        to-store (concat updated-records new-records)]
    (when (seq to-store)
      (db2/store-grocery-items to-store))
    (account-svc/store-account-last-sync user-id (:account-id account) (str (Instant/now)))
    {:imported (count new-records)
     :updated (count updated-records)
     :total (count to-store)
     :provider provider
     :account-id (:account-id account)}))

(defn sync-all!
  "Sync all grocery accounts for a user. Optional account-id filters to one.
   Returns {:results [...] :errors [...]}."
  [user-id & {:keys [account-id]}]
  (let [accounts (->> (db2/get-accounts-for-user user-id)
                      (filter #(= :grocery (:type (account-svc/get-provider (:provider %)))))
                      (filter #(or (nil? account-id) (= account-id (:account-id %)))))
        results (atom [])
        errors (atom [])]
    (doseq [acc accounts]
      (try
        ;; Need secrets: get-accounts-for-user includes client-secret
        (swap! results conj (sync-account! user-id acc))
        (catch Exception e
          (println "grocery sync failed for" (:provider acc) (:account-id acc) ":" (.getMessage e))
          (swap! errors conj {:account-id (:account-id acc)
                              :provider (:provider acc)
                              :error (.getMessage e)}))))
    {:results @results
     :errors @errors
     :imported (reduce + 0 (map :imported @results))
     :updated (reduce + 0 (map :updated @results))}))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn items-handler [request]
  (try
    (let [user-id (:user-id request)
          items (db2/get-grocery-items user-id)
          ;; Newest first for UI
          sorted (reverse items)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:items sorted})})
    (catch Exception e
      (println "grocery items-handler error:" (.getMessage e))
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (.getMessage e)})})))

(defn sync-handler [request]
  (try
    (let [user-id (:user-id request)
          account-id (some-> request :body :account-id)
          result (sync-all! user-id :account-id account-id)
          status (if (and (empty? (:results result)) (seq (:errors result)))
                   502
                   200)]
      {:status status
       :headers {"Content-Type" "application/json"}
       :body (json/write-str result)})
    (catch Exception e
      (println "grocery sync-handler error:" (.getMessage e))
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (.getMessage e)})})))
