(ns client.db
  (:require [cljs.spec.alpha :as s]
            [client.services.date-service :as date]))

;; period-selector
(s/def ::length #{:a-month :a-year})
(s/def ::month-index string?)
(s/def ::year (s/nilable string?))
(s/def ::a-month (s/keys :req-un [::month-index ::year]))
(s/def ::a-year (s/keys :req-un [::year]))
(s/def ::transaction-years (s/coll-of number? :kind vector?))
(s/def ::period-selector (s/keys :req-un [::length ::a-month ::a-year ::transaction-years]))

;; period
(s/def ::start inst?)
(s/def ::end inst?)
(s/def ::period (s/keys :req-un [::start ::end]))

;; period-selector2
(s/def ::time-unit #{:day :month :year})
(s/def ::unit-index string?)
(s/def ::short-view (s/keys :req-un [::unit-index]))
(s/def ::long-view (s/coll-of ::period :kind vector?))
(s/def ::period-selector2 (s/keys :req-un [::time-unit ::short-view ::long-view]))

;; filter-path
(s/def ::filter-path (s/coll-of string? :kind vector?))

; period-length
; what period
; one-year-back year-x
; month-x-at-year-x
; start and end

(def period-selector {:length :ayear
                      :amonth {:month-index 2
                              :year 2007}
                      :ayear {:year 2007}}) ; if :year is nil take one-year-back
(def period-selector2 {:time-unit :month
                       :short-view {:unit-index "0"}
                       :long-view [{:start 2134 :end 4312} {:start 2343 :end 4313}]})

(s/def ::db (s/keys :req-un [::period
                             ::period-selector
                             ::filter-path]))

(def default-db
    (let [month-index (date/current-month)
          year (date/current-year)
          unit-index (date/current-month)
          long-view (date/long-view :month)
          ]
      {:period-selector2 {:time-unit :month
                          :short-view {:unit-index unit-index}
                          :long-view long-view}}
      {:period-selector {:length :a-month
                         :transaction-years []
                         :a-month {:month-index month-index
                                   :year year}
                         :a-year {:year nil}}
       :period {:start (date/first-day-of-month month-index year)
                :end (date/first-day-of-next-month month-index year)}
       :filter-path []
       :displayed-transactions-data {:display-option :table}})
  )