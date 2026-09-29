(ns odin.services.merge-service-test
  (:require [clojure.test :refer [deftest is testing]]
            [odin.services.merge-service :as merge]
            [odin.services.date-service :as date]))

(defn date->iso [iso-date]
  (-> iso-date date/iso-date-str->date date/date->unixtime))

(defn db-trans [amount iso-date date-index description]
  {:amount amount
   :date (date->iso iso-date)
   :date-index date-index
   :description description
   :user-id "u1"})

(defn bank-trans [amount iso-date description]
  (let [src {:amount amount :date (date->iso iso-date) :description description}]
    {:amount amount
     :date (date->iso iso-date)
     :description description
     :user-id "u1"
     :source src}))

(deftest near-amount-close-date-replaces
  (testing "GUSTAVSEN regression: amount differs by 0.02 and date by 7 days → replace, not duplicate"
    (let [[replacements new orphans]
          (merge/process-transactions-from-bank
           [(db-trans -6328.07 "2026-06-11" 0 "GUSTAVSEN AS")]
           [(bank-trans -6328.05 "2026-06-18" "GUSTAVSEN AS")])]
      (is (= 1 (count replacements)) "the bank row should replace the stale db row")
      (is (= 0 (count new)) "no new transaction should be created")
      (is (= 0 (count orphans)) "the db row is matched, so no orphan"))))

(deftest exact-duplicate-same-date-discarded
  (testing "identical amount, date and description → discarded (no new, no replacement)"
    (let [[replacements new orphans]
          (merge/process-transactions-from-bank
           [(db-trans -100.0 "2026-06-10" 0 "REMA 1000")]
           [(bank-trans -100.0 "2026-06-10" "REMA 1000")])]
      (is (= 0 (count replacements)))
      (is (= 0 (count new)))
      (is (= 0 (count orphans))))))

(deftest near-amount-different-merchant-not-merged
  (testing "near amount but different merchant → treated as new, db row becomes an orphan"
    (let [[replacements new orphans]
          (merge/process-transactions-from-bank
           [(db-trans -50.0 "2026-06-10" 0 "GUSTAVSEN AS")]
           [(bank-trans -50.02 "2026-06-10" "REMA 1000")])]
      (is (= 0 (count replacements)) "different descriptions must not merge")
      (is (= 1 (count new)) "the bank row is a genuinely new transaction")
      (is (= 1 (count orphans)) "the unmatched db row is an orphan in-window"))))

(deftest amount-beyond-tolerance-not-merged
  (testing "amount difference beyond ±0.10 → not merged even with identical merchant"
    (let [[replacements new orphans]
          (merge/process-transactions-from-bank
           [(db-trans -50.0 "2026-06-10" 0 "GUSTAVSEN AS")]
           [(bank-trans -50.20 "2026-06-10" "GUSTAVSEN AS")])]
      (is (= 0 (count replacements)))
      (is (= 1 (count new)))
      (is (= 1 (count orphans))))))

(deftest orphan-detection-within-window
  (testing "a db transaction inside the pulled window with no bank match is returned as an orphan"
    (let [[replacements new orphans]
          (merge/process-transactions-from-bank
           [(db-trans -100.0 "2026-06-10" 0 "A")
            (db-trans -200.0 "2026-06-12" 0 "B")
            (db-trans -300.0 "2026-06-14" 0 "C")]
           [(bank-trans -100.0 "2026-06-10" "A")
            (bank-trans -300.0 "2026-06-14" "C")])]
      (is (= 0 (count replacements)))
      (is (= 0 (count new)))
      (is (= 1 (count orphans)) "only B is unaccounted for")
      (is (= {:user-id "u1" :date (date->iso "2026-06-12") :date-index 0}
             (first orphans))
          "orphan is shaped for deletion: :user-id :date :date-index"))))

(deftest orphan-outside-window-ignored
  (testing "a db transaction after the last pulled bank date is not flagged as an orphan"
    (let [[_ _ orphans]
          (merge/process-transactions-from-bank
           [(db-trans -100.0 "2026-06-10" 0 "A")
            (db-trans -200.0 "2026-06-20" 0 "B")]
           [(bank-trans -100.0 "2026-06-10" "A")])]
      (is (= 0 (count orphans)) "B is outside the pulled window, so it is left untouched"))))
