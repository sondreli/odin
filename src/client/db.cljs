(ns client.db
  (:require [cljs.spec.alpha :as s]
            [client.services.date-service :as date]))

;; period
(s/def ::start inst?)
(s/def ::end inst?)
(s/def ::period (s/keys :req-un [::start ::end]))

;; period-selector
(s/def ::time-unit #{:day :month :year})
(s/def ::unit-index string?)
(s/def ::short-view (s/keys :req-un [::unit-index]))
(s/def ::long-view (s/coll-of ::period :kind vector?))
(s/def ::transaction-years (s/coll-of number? :kind vector?))
(s/def ::period-selector (s/keys :req-un [::time-unit ::short-view ::long-view ::transaction-years]))

;; filter-path
(s/def ::filter-path (s/coll-of string? :kind vector?))

(s/def ::open-category-row number?)

; period-length
; what period
; one-year-back year-x
; month-x-at-year-x
; start and end

(def period-selector {:time-unit :month
                      :short-view {:unit-index "0"}
                      :long-view [{:start 2134 :end 4312} {:start 2343 :end 4313}]})

(s/def ::db (s/keys :req-un [::period
                             ::period-selector
                             ::filter-path
                             ::open-category-row]))

(def default-db
    (let [month-index (date/current-month)
          year (date/current-year)
          unit-index (date/current-month)
          long-view (date/init-long-view)
          ]
      (println "default-db/long-view: " long-view)
      {:period-selector {:time-unit :month
                         :transaction-years []
                         :short-view {:unit-index unit-index}
                         :long-view long-view
                         :selected-period (last long-view)}
       :period {:start (date/first-day-of-month month-index year)
                :end (date/first-day-of-next-month month-index year)}
       :filter-path []
       :open-category-row nil
       :displayed-transactions-data {:display-option :table}})
  )