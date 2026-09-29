(ns odin.services.price-service
  "Fetch daily split-adjusted close prices (converted to NOK) from Yahoo Finance and
   cache them per ISIN/day. Used by the wealth service to value holdings over time.

   No official API exists; the Yahoo `chart` endpoint is unofficial but free. Prices use
   `adjclose` (split-adjusted) which matches the wealth service's current-share basis."
  (:require [clojure.data.json :as json]
            [odin.db2 :as db2]
            [odin.services.date-service :as date])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Instant ZoneId]))

(def ^:private oslo (ZoneId/of "Europe/Oslo"))

;; ISIN -> {:ticker yahoo-symbol :currency "USD"|"NOK"}.
;; Seeded from the securities seen in Nordnet exports; extend as needed.
(def ticker-map
  {"US88160R1014" {:ticker "TSLA" :currency "USD"}   ;; Tesla
   "US69608A1088" {:ticker "PLTR" :currency "USD"}   ;; Palantir
   "US46428Q1094" {:ticker "SLV"  :currency "USD"}   ;; iShares Silver Trust
   "US0395872098" {:ticker "FUV"  :currency "USD"}   ;; Arcimoto
   "US0395871009" {:ticker "FUV"  :currency "USD"}}) ;; Arcimoto (post ticker change)

(def ^:private fx-symbol "USDNOK=X")

(def ^:private http-client (delay (HttpClient/newHttpClient)))

(defn- http-get [url]
  (let [req (-> (HttpRequest/newBuilder (URI/create url))
                (.header "User-Agent" "Mozilla/5.0 (compatible; odin/1.0)")
                (.GET)
                (.build))
        resp (.send ^HttpClient @http-client req (HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode resp))
      (.body resp))))

(defn- unixsec->iso [sec]
  (-> (Instant/ofEpochSecond (long sec)) (.atZone oslo) (.toLocalDate) (.toString)))

(defn- chart-url [symbol p1 p2]
  (format "https://query1.finance.yahoo.com/v8/finance/chart/%s?period1=%d&period2=%d&interval=1d"
          symbol (long p1) (long p2)))

(defn fetch-series
  "Fetch {iso-date -> price} for a Yahoo symbol between unix seconds p1..p2.
   When adjusted? use split-adjusted close, else raw close. Null points are dropped."
  [symbol p1 p2 adjusted?]
  (try
    (when-let [body (http-get (chart-url symbol p1 p2))]
      (let [data (json/read-str body :key-fn keyword)
            r (-> data :chart :result first)
            ts (:timestamp r)
            vals (if adjusted?
                   (-> r :indicators :adjclose first :adjclose)
                   (-> r :indicators :quote first :close))]
        (->> (map vector ts vals)
             (keep (fn [[t v]] (when (and t v) [(unixsec->iso t) (double v)])))
             (into {}))))
    (catch Exception e
      (println "Yahoo fetch failed for" symbol ":" (.getMessage e))
      nil)))

(defn- last-on-or-before
  "Most recent value in date->value map whose date <= iso-date (forward-fill)."
  [m iso-date sorted-dates]
  (let [candidates (take-while #(<= (compare % iso-date) 0) sorted-dates)]
    (when (seq candidates)
      (get m (last candidates)))))

(defn fetch-isin-prices-nok
  "Fetch split-adjusted daily prices for one ISIN, converted to NOK, as {iso-date -> nok}.
   Returns nil for unknown ISINs."
  [isin p1 p2]
  (when-let [{:keys [ticker currency]} (ticker-map isin)]
    (let [closes (fetch-series ticker p1 p2 true)]
      (when (seq closes)
        (if (= currency "NOK")
          closes
          (let [fx (fetch-series fx-symbol p1 p2 false)
                fx-dates (sort (keys fx))]
            (when (seq fx)
              (->> closes
                   (keep (fn [[d px]]
                           (when-let [rate (or (get fx d) (last-on-or-before fx d fx-dates))]
                             [d (* px rate)])))
                   (into {})))))))))

(defn- latest-cached-ms
  "Epoch-ms of the most recent cached price for an ISIN, or nil if none."
  [isin]
  (some->> (db2/get-security-prices isin)
           (keep :price-date)
           seq
           (apply max-key #(-> % date/iso-date-str->date date/date->unixtime))
           (#(-> % date/iso-date-str->date date/date->unixtime))))

(defn refresh-prices
  "Incrementally fetch & cache daily NOK prices for a user's holdings. For each ISIN we only
   fetch from the latest cached day onward (re-fetching that day to update the latest close),
   or from a week before the first trade if nothing is cached. Returns {isin count}."
  [user-id]
  (let [txns (db2/get-investment-transactions user-id)]
    (if (empty? txns)
      {}
      (let [isins (distinct (keep :isin txns))   ;; keep, not map — cash rows have no :isin
            min-ms (apply min (map :date txns))
            p2 (quot (+ (System/currentTimeMillis) 86400000) 1000)]
        (reduce (fn [acc isin]
                  (let [start-ms (or (latest-cached-ms isin) (- min-ms (* 7 86400000)))
                        p1 (quot start-ms 1000)]
                    (if-let [prices (fetch-isin-prices-nok isin p1 p2)]
                      (do (db2/store-security-prices
                            (map (fn [[d nok]] {:isin isin :price-date d :price-nok nok}) prices))
                          (assoc acc isin (count prices)))
                      (assoc acc isin 0))))
                {} isins)))))

;; ---------------------------------------------------------------------------
;; Lookup fn for the wealth service
;; ---------------------------------------------------------------------------

(defn price-lookup-fn
  "Build (fn [isin cutoff-ms] -> price-nok|nil) from cached prices for the given ISINs.
   Returns the most recent cached price strictly before cutoff-ms (forward-fill)."
  [isins]
  (let [by-isin (into {}
                      (for [isin isins]
                        (let [pts (->> (db2/get-security-prices isin)
                                       (keep (fn [{:keys [price-date price-nok]}]
                                               (when (and price-date price-nok)
                                                 [(-> price-date date/iso-date-str->date date/date->unixtime)
                                                  price-nok])))
                                       (sort-by first)
                                       vec)]
                          [isin pts])))]
    (fn [isin cutoff-ms]
      (when-let [pts (seq (get by-isin isin))]
        (let [before (take-while #(< (first %) cutoff-ms) pts)]
          (when (seq before)
            (second (last before))))))))

;; ---------------------------------------------------------------------------
;; Handler
;; ---------------------------------------------------------------------------

(defn refresh-handler [request]
  (try
    (let [user-id (:user-id request)
          result (refresh-prices user-id)]
      (db2/set-prices-refreshed-at user-id (System/currentTimeMillis))
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:refreshed result})})
    (catch Exception e
      ;; Return a proper response so CORS headers are still applied (an uncaught throw
      ;; yields a header-less 500 that the browser reports as a CORS error).
      (println "Price refresh error:" (.getMessage e))
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (str "Price refresh failed: " (.getMessage e))})})))
