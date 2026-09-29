(ns common.category-service-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as s]
            [common.category-service :as c]))

(deftest longest-common-substring-test
  (testing "shared contiguous substring across all strings"
    (is (= "rema 1000 " (c/longest-common-substring
                         ["REMA 1000 OSLO 12/05" "REMA 1000 BERGEN 03/06"]))))
  (testing "single string returns itself (lowercased)"
    (is (= "kiwi oslo" (c/longest-common-substring ["KIWI OSLO"]))))
  (testing "no shared substring"
    (is (nil? (c/longest-common-substring ["abc" "xyz"]))))
  (testing "empty input"
    (is (nil? (c/longest-common-substring [])))))

(deftest synthesize-filter-literal-test
  (testing "prefers a clean shared literal, no regex needed"
    (let [pos ["REMA 1000 OSLO 12/05" "REMA 1000 BERGEN 03/06"]
          neg ["KIWI OSLO"]
          {:keys [pattern regex? clean?]} (c/synthesize-filter pos neg)]
      (is (= false regex?))
      (is (= true clean?))
      (is (every? #(c/match-fun % pattern) pos))
      (is (not-any? #(c/match-fun % pattern) neg)))))

(deftest synthesize-filter-substring-contamination-test
  (testing "a distinguishing token that is a substring of a negative word must not be chosen"
    ;; 'rema' appears inside 'remarket' in the negative, so a plain 'rema' filter
    ;; would wrongly match it; the synthesizer must pick a word-bounded fragment.
    (let [pos ["REMA 1000 OSLO"]
          neg ["EXTRA REMARKET OSLO"]
          {:keys [pattern clean?]} (c/synthesize-filter pos neg)]
      (is (= true clean?))
      (is (every? #(c/match-fun % pattern) pos))
      (is (not-any? #(c/match-fun % pattern) neg))
      ;; specifically, the bare contaminated token is not the suggestion
      (is (not= "rema" pattern)))))

(deftest synthesize-filter-charclass-context-test
  (testing "anchors on a shared stem and distinguishes by the adjacent character (POWER vs POWERED)"
    (let [pos ["Vipps*Power.no" "POWER CITY" "POWER STØPERIVEIEN STRØMMEN"
               "POWER HOVEVEGEN 6 SOGNDAL" "POWER DRONINGENSGT OSLO"]
          neg ["CURSOR, AI POWERED IDE" "WWW.STEAMPOWERED.COM"]
          {:keys [pattern clean?]} (c/synthesize-filter pos neg)]
      (is (= true clean?))
      (is (every? #(c/match-fun % pattern) pos))
      (is (not-any? #(c/match-fun % pattern) neg))
      (is (s/includes? pattern "power"))))

(deftest synthesize-filter-negative-exclusion-test
  (testing "excludes a dropped right-neighbour word rather than overfitting to positive first letters"
    ;; Base filter "obs " matches all OBS… rows; dropping OBS BYGG should yield a
    ;; negative lookahead on "bygg", not regex:obs [hsv] (which misses future stores).
    (let [pos ["OBS VINTERBRO SJØSKOGVEIEN VINTERBRO"
               "OBS HAUGENSTUA ØSTRE AKER V OSLO"
               "OBS SANDEFJORD 837957"]
          neg ["OBS BYGG NYGÅRD KVELDROVEIEN VINTERBRO"]
          {:keys [pattern regex? clean?]} (c/synthesize-filter pos neg "obs ")]
      (is (= true clean?))
      (is (= true regex?))
      (is (every? #(c/match-fun % pattern) pos))
      (is (not-any? #(c/match-fun % pattern) neg))
      (is (re-find #"\(\?!.*bygg" pattern))
      (is (not (re-find #"\[[^\]]*\]" pattern)))
      (is (= "regex:obs (?!bygg\\b)" pattern))))))

(deftest synthesize-filter-boundary-or-test
  (testing "ORs different boundary conditions of the same stem (^ark OR *ark) vs pARK"
    (let [pos ["ARK HOLMLIA HOLMLIAVEIEN OSLO" "Vipps*ARK.NO" "ARK HOLMLIA SEN HOLMLIAVEIEN OSLO"]
          neg ["BANE NOR PARKERINGS AP" "Aimo Park Norway AS (ANPR GL)"]
          {:keys [pattern regex? clean?]} (c/synthesize-filter pos neg "ark")]
      (is (= true regex?))
      (is (= true clean?))
      (is (every? #(c/match-fun % pattern) pos))
      (is (not-any? #(c/match-fun % pattern) neg))
      ;; anchored on the stem, and combines start-of-string with the *-prefix case
      (is (s/includes? pattern "ark"))
      (is (s/includes? pattern "^")))))

(deftest synthesize-filter-anchor-context-test
  (testing "centers on the common word and distinguishes via surrounding tokens"
    (let [pos ["PAY REMA OSLO" "GET REMA BERGEN"]
          neg ["SEE REMA STORE" "REMA PARKING"]
          {:keys [pattern clean?]} (c/synthesize-filter pos neg)]
      (is (= true clean?))
      (is (every? #(c/match-fun % pattern) pos))
      (is (not-any? #(c/match-fun % pattern) neg))
      ;; the suggestion is anchored on the shared word
      (is (s/includes? pattern "rema")))))

(deftest filter-stem-test
  (testing "plain filter -> itself (lowercased); regex -> first alphanumeric run"
    (is (= "kjell" (c/filter-stem "kjell")))
    (is (= "kjell" (c/filter-stem "regex:^kjell")))
    (is (= "power" (c/filter-stem "regex:power[ .]")))
    (is (= "rema" (c/filter-stem "regex:rema (oslo|bergen)")))
    (is (nil? (c/filter-stem "")))
    (is (nil? (c/filter-stem nil)))))

(deftest synthesize-filter-required-positional-test
  (testing "keeps the seed stem and anchors positionally (^kjell) rather than drifting to a stray token"
    (let [pos ["KJELL.COM/NO" "KJELL &CO NO005 GAMLEVEIEN 8 RASTA"
               "KJELL.COM/NO" "KJELL & CONO013 TORGGATA 6 OSLO"]
          neg ["Blåskjell 20220611 18:41" "CIRCLE K KJELLE FETVEIEN 175 KJELLER"]
          {:keys [pattern clean?]} (c/synthesize-filter pos neg "kjell")]
      (is (= "regex:^kjell" pattern))
      (is (= true clean?))
      (is (every? #(c/match-fun % pattern) pos))
      (is (not-any? #(c/match-fun % pattern) neg))))
  (testing "without the required stem the synthesizer is free to pick a stray common token"
    ;; "no" is common to all positives and absent from negatives, so an unconstrained
    ;; synthesis may pick it — which is exactly what the required stem prevents.
    (let [pos ["KJELL.COM/NO" "KJELL &CO NO005 GAMLEVEIEN 8 RASTA"
               "KJELL.COM/NO" "KJELL & CONO013 TORGGATA 6 OSLO"]
          neg ["Blåskjell 20220611 18:41" "CIRCLE K KJELLE FETVEIEN 175 KJELLER"]
          required-pat (:pattern (c/synthesize-filter pos neg "kjell"))]
      (is (clojure.string/includes? required-pat "kjell")))))

(deftest synthesize-filter-alternation-test
  (testing "falls back to regex alternation when no single literal separates"
    (let [pos ["NETTO STORE 1" "BUNNPRIS SHOP"]
          neg ["KIWI MARKET"]
          {:keys [pattern regex? clean?]} (c/synthesize-filter pos neg)]
      (is (= true regex?))
      (is (= true clean?))
      ;; the synthesized pattern actually matches every positive and no negative
      (is (every? #(c/match-fun % pattern) pos))
      (is (not-any? #(c/match-fun % pattern) neg)))))

(deftest synthesize-filter-single-distinguishing-token-test
  (testing "separates with a clean literal when the shared text already excludes negatives"
    (let [pos ["KIWI OSLO"]
          neg ["KIWI BERGEN"]
          {:keys [pattern regex? clean?]} (c/synthesize-filter pos neg)]
      (is (= false regex?))
      (is (= true clean?))
      (is (every? #(c/match-fun % pattern) pos))
      (is (not-any? #(c/match-fun % pattern) neg)))))

(deftest synthesize-filter-unseparable-test
  (testing "returns a best-effort pattern and reports it is not clean"
    (let [{:keys [clean?]} (c/synthesize-filter ["FOO BAR"] ["FOO BAR"])]
      (is (= false clean?))))
  (testing "empty positives yields an empty, non-clean pattern"
    (let [{:keys [pattern clean?]} (c/synthesize-filter [] ["x"])]
      (is (= "" pattern))
      (is (= false clean?)))))

(deftest validate-examples-test
  (testing "maps each transaction key to whether the pattern matches its description"
    (let [a {:date 1 :amount -10 :date-index 0 :description "REMA 1000 OSLO"}
          b {:date 2 :amount -20 :date-index 0 :description "KIWI OSLO"}
          d {:date 3 :amount -30 :date-index 0 :description nil}
          txns [a b d]]
      (is (= {(c/tx-key a) true (c/tx-key b) false (c/tx-key d) false}
             (c/validate-examples txns "rema 1000")))
      (is (= {(c/tx-key a) false (c/tx-key b) false (c/tx-key d) false}
             (c/validate-examples txns ""))))))

(deftest filter-diff-test
  (testing "splits matches into newly-categorized, stays-same, and conflicts"
    (let [txns [{:db-id "a" :description "REMA 1000 OSLO" :category-id nil}
                {:db-id "b" :description "REMA 1000 BERGEN" :category-id "mat"}
                {:db-id "c" :description "REMA 1000 TRH" :category-id "other"}
                {:db-id "d" :description "KIWI OSLO" :category-id nil}]
          diff (c/filter-diff txns "rema 1000" "mat")]
      (is (= 1 (:newly-categorized-count diff)))
      (is (= "a" (-> diff :newly-categorized first :db-id)))
      (is (= 1 (:stays-same-count diff)))
      (is (= 1 (:conflicts-count diff)))
      (is (= "c" (-> diff :conflicts first :db-id))))))
