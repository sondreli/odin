(ns odin.services.date-service
  (:import [java.time Duration Instant LocalDateTime OffsetDateTime ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]))

(defn unixtime->localtime [unixtime]
  (let [zoneIdOslo (ZoneId/of "Europe/Oslo")
        inst (Instant/ofEpochMilli unixtime)
        offset (-> zoneIdOslo .getRules (.getOffset inst))
        local-now (.atOffset inst offset)]
  local-now))

(defn unixtime->iso-date [unixtime]
  (let [zoneIdOslo (ZoneId/of "Europe/Oslo")
        inst (Instant/ofEpochMilli unixtime)
        offset (-> zoneIdOslo .getRules (.getOffset inst))
        local-now (.atOffset inst offset)
        iso-date (.format ^OffsetDateTime local-now DateTimeFormatter/ISO_LOCAL_DATE)]
  iso-date))

(defn localtime->unixtime [localtime]
  (let [formatter DateTimeFormatter/ISO_DATE_TIME
        local-date-time (LocalDateTime/parse ^CharSequence localtime formatter)
        instant (.atZone ^LocalDateTime local-date-time (ZoneId/of "Europe/Oslo"))]
    (.toEpochMilli (.toInstant ^ZonedDateTime instant))))

(defn iso-date-str->date [date-str]
  (let [datetime (str date-str "T00:00:00")
        formatter DateTimeFormatter/ISO_DATE_TIME
        local-date-time (LocalDateTime/parse ^CharSequence datetime formatter)
        date (.atZone ^LocalDateTime local-date-time (ZoneId/of "Europe/Oslo"))]
    date))

(defn date->unixtime [date]
  (if (instance? OffsetDateTime date)
    (.toEpochMilli (.toInstant ^OffsetDateTime date))
    (.toEpochMilli (.toInstant ^ZonedDateTime date))))

(defn days-since-transaction [transaction-date]
  (let [now (java.time.OffsetDateTime/now)
        transaction-time (unixtime->localtime transaction-date)
        time-diff (Duration/between transaction-time now)]
    (.toDays time-diff)))

(defn x-days-ago [days]
  (let [now (ZonedDateTime/now)
        x (.minus ^ZonedDateTime now ^long days ChronoUnit/DAYS)]
    x))

(defn subtract-days [date days]
  (if (instance? OffsetDateTime date)
    (.minus ^OffsetDateTime date ^long days ChronoUnit/DAYS)
    (.minus ^ZonedDateTime date ^long days ChronoUnit/DAYS)))

(defn add-days [date days]
  (if (instance? OffsetDateTime date)
    (.plus ^OffsetDateTime date ^long days ChronoUnit/DAYS)
    (.plus ^ZonedDateTime date ^long days ChronoUnit/DAYS)))

(defn find-retrieval-date [transaction-date]
  (let [days-since-last-db-transaction (days-since-transaction transaction-date)]
    (if (< 7 days-since-last-db-transaction)
      (unixtime->localtime transaction-date)
      (x-days-ago 7))))

(defn iso-date->local-datetime [iso-date]
  "Convert an ISO date string (e.g., '2024-01-15') to an OffsetDateTime object"
  (let [zoneIdOslo (ZoneId/of "Europe/Oslo")
        datetime (str iso-date "T00:00:00")
        formatter DateTimeFormatter/ISO_DATE_TIME
        local-date-time (LocalDateTime/parse ^CharSequence datetime formatter)
        zoned-datetime (.atZone ^LocalDateTime local-date-time zoneIdOslo)
        offset-datetime (.toOffsetDateTime ^ZonedDateTime zoned-datetime)]
    offset-datetime))
