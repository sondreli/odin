(ns odin.services.pace-service-test
  (:require [clojure.test :refer [deftest is testing]]
            [odin.services.pace-service :as pace])
  (:import [java.time LocalDate ZoneId]))

(def oslo (ZoneId/of "Europe/Oslo"))

(defn- at
  "Unix millis for a calendar day in Europe/Oslo."
  [iso]
  (-> (LocalDate/parse iso)
      (.atStartOfDay oslo)
      .toInstant
      .toEpochMilli))

(defn- monthly-txns
  [category-id description amount day dates]
  (mapv (fn [iso]
          {:date (at iso)
           :amount amount
           :description description
           :category-id category-id
           :date-index 0})
        dates))

(defn- find-cat
  [prediction id]
  (some #(when (= (str id) (:category-id %)) %) (:categories prediction)))

(defn- nth-day
  [cat day]
  (nth (:daily cat) (dec day)))

(deftest normalise-description-test
  (testing "dates, long references and card digits drop out, shop names stay"
    (is (= "netflix.com" (pace/normalise-description "NETFLIX.COM")))
    (is (= "netflix.com" (pace/normalise-description "NETFLIX.COM 482910337")))
    (is (= "rema 1000 oslo" (pace/normalise-description "REMA 1000 OSLO 12/05")))
    (is (= "vipps netflix" (pace/normalise-description "Vipps*123456 Netflix")))
    (is (= "" (pace/normalise-description nil)))))

(deftest norwegian-calendar-test
  (testing "Easter Sundays used by the holiday shift"
    (is (= (LocalDate/of 2024 3 31) (pace/easter-sunday 2024)))
    (is (= (LocalDate/of 2025 4 20) (pace/easter-sunday 2025)))
    (is (= (LocalDate/of 2026 4 5) (pace/easter-sunday 2026))))
  (testing "17 May and weekends are not business days, and payments move forward"
    (is (not (pace/business-day? (LocalDate/of 2026 5 17))))
    (is (not (pace/business-day? (LocalDate/of 2026 5 16))))
    (is (= 18 (pace/adjust-day 2026 5 17 :forward)))
    (is (= 15 (pace/adjust-day 2026 5 17 :backward)))
    (is (pace/business-day? (LocalDate/of 2026 5 18)))
    ;; 1 May 2026 is a Friday holiday, so the next business day is Monday 4 May.
    (is (= 4 (pace/adjust-day 2026 5 1 :forward)))
    ;; A payment already on a business day stays put.
    (is (= 15 (pace/adjust-day 2026 10 15 :forward)))))

(deftest recurring-payment-test
  (testing "same bill with changing references is one monthly payment at the latest amount"
    (let [dates ["2025-12-15" "2026-01-15" "2026-02-15" "2026-03-15" "2026-04-15"
                 "2026-05-15" "2026-06-15" "2026-07-15" "2026-08-15" "2026-09-15"]
          netflix (mapv (fn [iso idx]
                          {:date (at iso)
                           :amount (if (= iso "2026-09-15") -189.0 -179.0)
                           :description (str "NETFLIX.COM " (+ 100000 idx))
                           :category-id "media"
                           :date-index 0})
                        dates
                        (range 1 11))
          groceries (mapv (fn [iso]
                            {:date (at iso)
                             :amount -640.0
                             :description (str "REMA 1000 OSLO " (subs iso 8))
                             :category-id "mat"
                             :date-index 0})
                          ["2026-09-02" "2026-09-06" "2026-09-11" "2026-09-18" "2026-09-24"
                           "2026-08-03" "2026-08-12" "2026-08-21"])
          prediction (pace/compute-prediction
                      {:transactions (vec (concat netflix groceries))
                       :categories [{:id "media" :target 500}
                                    {:id "mat" :target 8000}
                                    {:id "ukategorisert-out"}]
                       :loans []
                       :year 2026
                       :month 10})
          media (find-cat prediction "media")
          payment (first (:recurring media))]
      (is (= "monthly" (:cadence payment)))
      (is (= 189.0 (:expected-amount payment)))
      (is (= 15 (:expected-day payment)))
      (is (= "detected" (:source payment)))
      (is (= 189.0 (nth-day media 15)))
      (is (= 0.0 (nth-day media 14)))
      (is (empty? (:recurring (find-cat prediction "mat"))))
      (is (nil? (find-cat prediction "ukategorisert-out")))))
  (testing "a payment inside the predicted month does not change the frozen curve"
    (let [history (monthly-txns "media" "NETFLIX.COM" -179.0 15
                                ["2025-12-15" "2026-01-15" "2026-02-15" "2026-03-15"
                                 "2026-04-15" "2026-05-15" "2026-06-15" "2026-07-15"
                                 "2026-08-15" "2026-09-15"])
          base {:categories [{:id "media" :target 500}] :loans [] :year 2026 :month 10}
          without (pace/compute-prediction (assoc base :transactions history))
          with (pace/compute-prediction
                (assoc base :transactions
                       (conj history {:date (at "2026-10-15")
                                      :amount -179.0
                                      :description "NETFLIX.COM"
                                      :category-id "media"})))]
      (is (= (:daily (find-cat without "media"))
             (:daily (find-cat with "media"))))))
  (testing "the same October payment is marked paid without moving the curve"
    (let [history (monthly-txns "media" "NETFLIX.COM" -179.0 15
                                ["2025-12-15" "2026-01-15" "2026-02-15" "2026-03-15"
                                 "2026-04-15" "2026-05-15" "2026-06-15" "2026-07-15"
                                 "2026-08-15" "2026-09-15"])
          prediction (pace/compute-prediction
                      {:transactions history
                       :categories [{:id "media" :target 500}]
                       :loans []
                       :year 2026
                       :month 10})
          paid (pace/annotate-paid prediction
                                  [{:date (at "2026-10-15")
                                    :amount -179.0
                                    :description "NETFLIX.COM 998877"
                                    :category-id "media"}])
          unpaid (pace/annotate-paid prediction [])]
      (is (= (:daily (find-cat prediction "media"))
             (:daily (find-cat paid "media"))))
      (is (true? (:paid? (first (:recurring (find-cat paid "media"))))))
      (is (false? (:paid? (first (:recurring (find-cat unpaid "media")))))))))

(deftest loan-payment-is-reused-test
  (testing "loan filters claim the transactions so they are not detected twice"
    (let [dates (map #(format "2026-%02d-20" %) (range 1 10))
          txns (monthly-txns "bolig" "Husbanken Avdrag 12345678901" -8500.0 20 dates)
          prediction (pace/compute-prediction
                      {:transactions txns
                       :categories [{:id "bolig" :target 9000}]
                       :loans [{:id "loan-1"
                                :name "Huslån"
                                :filter-texts ["husbanken"]
                                :monthly-payment 8500}]
                       :year 2026
                       :month 10})
          payments (:recurring (find-cat prediction "bolig"))]
      (is (= 1 (count payments)))
      (is (= "loan" (:source (first payments))))
      (is (= "Huslån" (:description (first payments))))
      (is (= 8500.0 (:expected-amount (first payments))))
      (is (= (pace/adjust-day 2026 10 20 :forward) (:expected-day (first payments)))))))

(deftest weekend-shift-test
  (testing "a bill whose usual day is a Sunday moves to the next business day"
    (let [;; 17 May 2026 is a Sunday. Historical payments on the 17th give no
          ;; earlier/later preference, so the default is to move forward.
          dates ["2025-08-17" "2025-09-17" "2025-10-17" "2025-11-17" "2025-12-17"
                 "2026-01-17" "2026-02-17" "2026-03-17" "2026-04-17"]
          prediction (pace/compute-prediction
                      {:transactions (monthly-txns "media" "NETFLIX.COM" -179.0 17 dates)
                       :categories [{:id "media" :target 200}]
                       :loans []
                       :year 2026
                       :month 5})
          payment (first (:recurring (find-cat prediction "media")))]
      (is (not (pace/business-day? (LocalDate/of 2026 5 17))))
      (is (= 18 (:expected-day payment)))
      (is (= 0.0 (nth-day (find-cat prediction "media") 17)))
      (is (= 179.0 (nth-day (find-cat prediction "media") 18))))))

(deftest quarterly-and-yearly-test
  (let [quarterly (monthly-txns "forsikring" "IF FORSIKRING" -2400.0 10
                                ["2025-01-10" "2025-04-10" "2025-07-10" "2025-10-10"
                                 "2026-01-10" "2026-04-10" "2026-07-10"])
        yearly (monthly-txns "forsikring" "GJENSIDIGE ARS" -4800.0 5
                             ["2023-10-05" "2024-10-05" "2025-10-05"])
        categories [{:id "forsikring" :target 3000}]]
    (testing "quarterly bill is due in October but not in May"
      (let [october (pace/compute-prediction {:transactions quarterly
                                              :categories categories
                                              :loans []
                                              :year 2026
                                              :month 10})
            may (pace/compute-prediction {:transactions quarterly
                                          :categories categories
                                          :loans []
                                          :year 2026
                                          :month 5})
            payment (first (:recurring (find-cat october "forsikring")))]
        (is (= "quarterly" (:cadence payment)))
        (is (= 2400.0 (:expected-amount payment)))
        (is (= (pace/adjust-day 2026 10 10 :forward) (:expected-day payment)))
        (is (nil? (find-cat may "forsikring")))))
    (testing "yearly bill is due only in its calendar month"
      (let [october (pace/compute-prediction {:transactions yearly
                                              :categories categories
                                              :loans []
                                              :year 2026
                                              :month 10})
            november (pace/compute-prediction {:transactions yearly
                                               :categories categories
                                               :loans []
                                               :year 2026
                                               :month 11})
            payment (first (:recurring (find-cat october "forsikring")))]
        (is (= "yearly" (:cadence payment)))
        (is (= 4800.0 (:expected-amount payment)))
        (is (= (pace/adjust-day 2026 10 5 :forward) (:expected-day payment)))
        (is (nil? (find-cat november "forsikring")))))))

(deftest variable-shape-and-fallback-test
  (testing "spending that always lands on the last day stays near zero at mid-month"
    (let [months ["2026-04-30" "2026-05-31" "2026-06-30" "2026-07-31" "2026-08-31" "2026-09-30"]
          txns (mapv (fn [iso idx]
                       {:date (at iso)
                        :amount -3100.0
                        :description (str "BUTIKK " idx " " iso)
                        :category-id "mat"
                        :date-index idx})
                     months
                     (range))
          prediction (pace/compute-prediction
                      {:transactions txns
                       :categories [{:id "mat" :target 4000}]
                       :loans []
                       :year 2026
                       :month 10})
          mat (find-cat prediction "mat")]
      (is (= "variable" (:method mat)))
      (is (false? (:straight-line? mat)))
      (is (< (nth-day mat 15) 200.0))
      (is (= 3100.0 (nth-day mat 31)))
      (is (nil? (:band-low mat)))))
  (testing "a chaotic category falls back to a straight line and has no band"
    (let [dates ["2025-10-03" "2025-11-03" "2025-12-03" "2026-01-03" "2026-02-03" "2026-03-03"
                 "2026-04-03" "2026-05-03" "2026-06-03" "2026-07-03" "2026-08-03" "2026-09-03"]
          txns (mapv (fn [iso amount idx]
                       {:date (at iso)
                        :amount amount
                        :description (str "KAFE " idx)
                        :category-id "ute"})
                     dates
                     [-100.0 -5000.0 -100.0 -5000.0 -100.0 -5000.0
                      -100.0 -5000.0 -100.0 -5000.0 -100.0 -5000.0]
                     (range))
          prediction (pace/compute-prediction
                      {:transactions txns
                       :categories [{:id "ute" :target 2000}]
                       :loans []
                       :year 2026
                       :month 10})
          ute (find-cat prediction "ute")]
      (is (true? (:straight-line? ute)))
      (is (nil? (:band-low ute)))
      (is (pos? (nth-day ute 1)))
      (is (< (nth-day ute 1) (nth-day ute 31)))))
  (testing "moderate variation keeps a curve and stores a 25th-75th band"
    ;; Days jump around so this is not a monthly bill, only a noisy total.
    (let [dates ["2026-04-02" "2026-05-19" "2026-06-07" "2026-07-03" "2026-08-21" "2026-09-28"]
          txns (mapv (fn [iso amount]
                       {:date (at iso)
                        :amount amount
                        :description (str "VAR " iso)
                        :category-id "hobby"})
                     dates
                     [-500.0 -500.0 -500.0 -2000.0 -2000.0 -2000.0])
          prediction (pace/compute-prediction
                      {:transactions txns
                       :categories [{:id "hobby" :target 3000}]
                       :loans []
                       :year 2026
                       :month 10})
          hobby (find-cat prediction "hobby")]
      (is (false? (:straight-line? hobby)))
      (is (= (count (:daily hobby)) (count (:band-low hobby)) (count (:band-high hobby))))
      (is (every? true? (map <= (:band-low hobby) (:band-high hobby))))))
  (testing "no history uses the budget as a straight line"
    (let [prediction (pace/compute-prediction
                      {:transactions []
                       :categories [{:id "mat" :target 3100}]
                       :loans []
                       :year 2026
                       :month 10})
          mat (find-cat prediction "mat")]
      (is (= "straight-line" (:method mat)))
      (is (= 100.0 (nth-day mat 1)))
      (is (= 3100.0 (nth-day mat 31)))))
  (testing "uncategorised spending is left out of the prediction"
    (let [txns (monthly-txns nil "NOE UKJENT" -400.0 4
                             ["2026-06-04" "2026-07-04" "2026-08-04" "2026-09-04"])
          prediction (pace/compute-prediction
                      {:transactions txns
                       :categories [{:id "mat" :target 3100}]
                       :loans []
                       :year 2026
                       :month 10})
          mat (find-cat prediction "mat")]
      (is (= ["mat"] (mapv :category-id (:categories prediction))))
      ;; The budget line is the only signal. The uncategorised 400 kr is not added.
      (is (= 3100.0 (nth-day mat 31))))))
