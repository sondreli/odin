(ns odin.services.date-service)

(defn unixtime->localtime [unixtime]
  (let [zoneIdOslo (java.time.ZoneId/of "Europe/Oslo")
        inst (java.time.Instant/ofEpochMilli unixtime)
        offset (-> zoneIdOslo .getRules (.getOffset inst))
        local-now (.atOffset inst offset)]
  local-now))

(defn unixtime->iso-date [unixtime]
  (let [zoneIdOslo (java.time.ZoneId/of "Europe/Oslo")
        inst (java.time.Instant/ofEpochMilli unixtime)
        offset (-> zoneIdOslo .getRules (.getOffset inst))
        local-now (.atOffset inst offset)
        iso-date (.format local-now java.time.format.DateTimeFormatter/ISO_LOCAL_DATE)]
  iso-date))

(defn localtime->unixtime [localtime]
  (let [;formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-ddTHH:mm:ss")
        formatter java.time.format.DateTimeFormatter/ISO_DATE_TIME
        local-date-time (. java.time.LocalDateTime parse localtime formatter)
        instant (.atZone local-date-time (java.time.ZoneId/of "Europe/Oslo"))]
    (.toEpochMilli (.toInstant instant))))

(defn iso-date-str->date [date-str]
  (let [datetime (str date-str "T00:00:00")
        formatter java.time.format.DateTimeFormatter/ISO_DATE_TIME
        local-date-time (. java.time.LocalDateTime parse datetime formatter)
        date (.atZone local-date-time (java.time.ZoneId/of "Europe/Oslo"))]
    date))

(defn date->unixtime [date]
  (.toEpochMilli (.toInstant date)))

;; (localtime->unixtime "2024-12-01T00:00:00")

(defn days-since-transaction [transaction-date]
  (let [;last-db-transaction (get-last-transaction db)
        now (java.time.ZonedDateTime/now)
        transaction-time (unixtime->localtime transaction-date)
        time-diff (java.time.Duration/between transaction-time now)]
    (.toDays time-diff)))

(defn x-days-ago [days]
  (let [now (java.time.ZonedDateTime/now)
        x (.minus now days (java.time.temporal.ChronoUnit/DAYS))]
    x))

(defn subtract-days [date days]
  (let [;now (java.time.ZonedDateTime/now)
        x (.minus date days (java.time.temporal.ChronoUnit/DAYS))]
    x))

(defn find-retrieval-date [transaction-date]
  (let [days-since-last-db-transaction (days-since-transaction transaction-date)]
    ;; (println days-since-last-db-transaction)
    (if (< 7 days-since-last-db-transaction)
      ;; (x-days-ago days-since-last-db-transaction)
      (unixtime->localtime transaction-date)
      (x-days-ago 7))))
