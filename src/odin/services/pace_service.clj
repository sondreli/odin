(ns odin.services.pace-service
  "Predicted cumulative spending for one user and one calendar month.

   The stored item is the frozen start-of-month baseline. Later reads keep that
   curve and only refresh whether each predicted recurring payment has been paid.
   A new algorithm version replaces the baseline, because the old curve would no
   longer match the code that draws it."
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [common.category-service :as category-match]
            [odin.db2 :as db2]
            [odin.services.category-service :as category-svc]
            [odin.services.loan-service :as loans])
  (:import [java.time DayOfWeek Instant LocalDate YearMonth ZoneId]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]))

(def algorithm-version 1)
(def amount-tolerance 0.125)
(def shape-months 12)
(def lookback-months 36)
(def chaotic-cv 0.8)
(def band-cv 0.35)
(def min-months-for-shape 3)
(def min-months-for-band 4)
(def recency-lambda 0.1)
(def day-mad-limit 4)

(def ^:private oslo (ZoneId/of "Europe/Oslo"))
(def ^:private month-fmt (DateTimeFormatter/ofPattern "yyyy-MM"))

(def excluded-category-ids #{"in" "out" "ukategorisert-in" "ukategorisert-out"})

;; ---------------------------------------------------------------------------
;; Dates and Norwegian bank holidays
;; ---------------------------------------------------------------------------

(defn ms->local-date
  [ms]
  (-> (Instant/ofEpochMilli (long ms))
      (.atZone oslo)
      .toLocalDate))

(defn month-key
  [^LocalDate date]
  (.format date month-fmt))

(defn parse-month-key
  "Returns [year month] for \"YYYY-MM\", or nil."
  [s]
  (when (and (string? s) (re-matches #"\d{4}-\d{2}" s))
    (let [year (Integer/parseInt (subs s 0 4))
          month (Integer/parseInt (subs s 5 7))]
      (when (<= 1 month 12)
        [year month]))))

(defn days-in-month
  [year month]
  (.lengthOfMonth (YearMonth/of year month)))

(defn- clamp-day
  [year month day]
  (min (max 1 (int day)) (days-in-month year month)))

(defn local-date
  [year month day]
  (LocalDate/of year month (clamp-day year month day)))

(defn easter-sunday
  "Anonymous Gregorian computus. Returns the Easter Sunday of `year`."
  [year]
  (let [a (mod year 19)
        b (quot year 100)
        c (mod year 100)
        d (quot b 4)
        e (mod b 4)
        f (quot (+ b 8) 25)
        g (quot (+ (- b f) 1) 3)
        h (mod (+ (* 19 a) (- b d g) 15) 30)
        i (quot c 4)
        k (mod c 4)
        l (mod (+ 32 (* 2 e) (* 2 i) (- h) (- k)) 7)
        m (quot (+ a (* 11 h) (* 22 l)) 451)
        base (+ h l (* -7 m) 114)
        month (quot base 31)
        day (inc (mod base 31))]
    (LocalDate/of year month day)))

(def norwegian-holidays
  "Public holidays that move a bank payment, for one calendar year.
   Built with `set` so a movable feast that lands on a fixed holiday
   (rare, but legal) does not throw."
  (memoize
   (fn [year]
     (let [easter (easter-sunday year)]
       (set [(LocalDate/of year 1 1)
             (.minusDays easter 3)   ;; skjærtorsdag
             (.minusDays easter 2)   ;; langfredag
             (.plusDays easter 1)    ;; andre påskedag
             (LocalDate/of year 5 1)
             (LocalDate/of year 5 17)
             (.plusDays easter 39)   ;; Kristi himmelfartsdag
             (.plusDays easter 50)   ;; andre pinsedag
             (LocalDate/of year 12 25)
             (LocalDate/of year 12 26)])))))

(defn business-day?
  [^LocalDate date]
  (let [dow (.getDayOfWeek date)]
    (and (not= dow DayOfWeek/SATURDAY)
         (not= dow DayOfWeek/SUNDAY)
         (not (contains? (norwegian-holidays (.getYear date)) date)))))

(defn- walk-to-business-day
  [^LocalDate start step]
  (let [month (.getMonthValue start)]
    (loop [date start guard 0]
      (cond
        (business-day? date) date
        (>= guard 10) start
        :else
        (let [candidate (.plusDays date step)]
          (if (= (.getMonthValue candidate) month)
            (recur candidate (inc guard))
            ;; Don't spill into the next or previous month.
            (loop [back start back-guard 0]
              (let [other (.plusDays back (- step))]
                (cond
                  (and (= (.getMonthValue other) month) (business-day? other)) other
                  (or (not= (.getMonthValue other) month) (>= back-guard 10)) start
                  :else (recur other (inc back-guard)))))))))))

(defn adjust-day
  "Move `day` off a weekend or holiday. `:forward` walks to the next business
   day (the usual AvtaleGiro rule); `:backward` walks to the previous one.
   The result stays inside the month, so a payment due on the 31st does not
   spill into the next month."
  [year month day direction]
  (let [start (local-date year month day)
        step (if (= direction :backward) -1 1)]
    (.getDayOfMonth (walk-to-business-day start step))))

(defn- previous-month-keys
  [year month n]
  (let [start (LocalDate/of year month 1)]
    (mapv (fn [i]
            (month-key (.minusMonths start i)))
          (range 1 (inc n)))))

(defn- months-between-keys
  [earlier later]
  (let [[y1 m1] (parse-month-key earlier)
        [y2 m2] (parse-month-key later)]
    (- (+ (* y2 12) m2) (+ (* y1 12) m1))))

;; ---------------------------------------------------------------------------
;; Description normalisation and amount grouping
;; ---------------------------------------------------------------------------

(defn normalise-description
  "Lowercase a bank description and drop the parts that change between months:
   dates, times, long references and card digits. Store names and amounts like
   \"REMA 1000\" are kept, so two shops do not collapse into one payment."
  [desc]
  (-> (or desc "")
      str/lower-case
      (str/replace #"\*" " ")
      (str/replace #"\d{4}-\d{2}-\d{2}" " ")
      (str/replace #"\d{1,2}[./]\d{1,2}[./]\d{2,4}" " ")
      (str/replace #"\d{1,2}[./]\d{1,2}" " ")
      (str/replace #"\d{1,2}:\d{2}" " ")
      (str/replace #"\d{6,}" " ")
      (str/replace #"\b(?:19|20)\d{2}\b" " ")
      (str/replace #"[^0-9a-zæøå.]+" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn- median
  [xs]
  (let [s (vec (sort xs))
        n (count s)
        mid (quot n 2)]
    (when (pos? n)
      (if (odd? n)
        (nth s mid)
        (/ (+ (double (nth s (dec mid))) (double (nth s mid))) 2.0)))))

(defn- percentile
  [xs p]
  (let [s (vec (sort xs))
        n (count s)]
    (when (pos? n)
      (let [rank (* (double p) (dec n))
            lo (int (Math/floor rank))
            hi (int (Math/ceil rank))
            frac (- rank lo)]
        (+ (* (- 1.0 frac) (double (nth s lo)))
           (* frac (double (nth s hi))))))))

(defn- mean
  [xs]
  (when (seq xs)
    (/ (reduce + 0.0 (map double xs)) (count xs))))

(defn coefficient-of-variation
  [xs]
  (let [mu (mean xs)]
    (if (or (nil? mu) (zero? mu))
      0.0
      (let [var (/ (reduce + 0.0 (map #(Math/pow (- (double %) mu) 2) xs))
                   (count xs))]
        (/ (Math/sqrt var) (Math/abs mu))))))

(defn amounts-close?
  [a b]
  (let [aa (Math/abs (double a))
        bb (Math/abs (double b))
        scale (max aa bb 1.0)]
    (<= (Math/abs (- aa bb)) (* amount-tolerance scale))))

(defn- cluster-by-amount
  "Greedy clusters of transactions whose absolute amounts stay within tolerance
   of the cluster median. Input must be sorted by amount."
  [txns]
  (reduce
   (fn [clusters txn]
     (let [amt (Math/abs (double (:amount txn)))
           idx (first
                (keep-indexed
                 (fn [i cluster]
                   (let [med (median (map #(Math/abs (double (:amount %))) cluster))]
                     (when (and med (<= (Math/abs (- amt med)) (* amount-tolerance med)))
                       i)))
                 clusters))]
       (if (some? idx)
         (update clusters idx conj txn)
         (conj clusters [txn]))))
   []
   txns))

;; ---------------------------------------------------------------------------
;; Recurring series
;; ---------------------------------------------------------------------------

(defn- txn-month-key
  [txn]
  (month-key (ms->local-date (:date txn))))

(defn- day-of-month
  [txn]
  (.getDayOfMonth (ms->local-date (:date txn))))

(defn- stable-day?
  [txns]
  (let [days (map day-of-month txns)
        mid (median days)
        mad (median (map #(Math/abs (- % mid)) days))]
    (and mid (<= mad day-mad-limit))))

(defn- median-count-per
  [txns key-fn]
  (let [counts (map count (vals (group-by key-fn txns)))]
    (median counts)))

(defn- too-frequent?
  [txns cadence]
  (let [med (case cadence
              :monthly (median-count-per txns txn-month-key)
              :quarterly (median-count-per txns (fn [t]
                                                  (let [d (ms->local-date (:date t))]
                                                    (str (.getYear d) "-" (quot (dec (.getMonthValue d)) 3)))))
              :yearly (median-count-per txns (fn [t]
                                               (.getYear (ms->local-date (:date t))))))]
    (and med (> med 1.5))))

(defn- month-gap-ok?
  [month-keys lo hi]
  (let [sorted (sort month-keys)
        gaps (map months-between-keys sorted (rest sorted))
        med (median gaps)]
    (and med (<= lo med hi))))

(defn- monthly-cadence?
  [month-keys target-year target-month]
  (let [last-12 (set (previous-month-keys target-year target-month 12))
        last-4 (set (previous-month-keys target-year target-month 4))
        present (set month-keys)]
    (or (>= (count (filter present last-4)) 3)
        (>= (count (filter present last-12)) 9))))

(defn- quarterly-cadence?
  [txns]
  (let [dates (map #(ms->local-date (:date %)) txns)
        residues (map #(mod (.getMonthValue %) 3) dates)
        freq (frequencies residues)
        [residue hits] (apply max-key val freq)
        month-keys (map month-key dates)]
    (and (>= hits 3)
         (>= (/ hits (count dates)) 0.75)
         (month-gap-ok? month-keys 2 4)
         residue)))

(defn- yearly-cadence?
  [txns]
  (let [dates (map #(ms->local-date (:date %)) txns)
        months (map #(.getMonthValue %) dates)
        freq (frequencies months)
        [month hits] (apply max-key val freq)
        years (distinct (map #(.getYear %) dates))]
    (and (>= hits 2)
         (>= (count years) 2)
         (>= (/ hits (count dates)) 0.75)
         (month-gap-ok? (map month-key dates) 8 16)
         month)))

(defn- similar-amounts?
  "True when every amount sits within tolerance of the largest one. A price
   change bigger than that is two different bills, not one series."
  [txns]
  (let [amts (map #(Math/abs (double (:amount %))) txns)
        hi (apply max 0.0 amts)
        lo (apply min hi amts)]
    (or (zero? hi)
        (<= (- hi lo) (* amount-tolerance (max hi 1.0))))))

(defn- detect-cadence
  [txns target-year target-month]
  (let [month-keys (map txn-month-key txns)]
    (cond
      (and (monthly-cadence? month-keys target-year target-month)
           (stable-day? txns)
           (not (too-frequent? txns :monthly)))
      {:cadence :monthly}

      :else
      (if-let [residue (quarterly-cadence? txns)]
        (when (and (stable-day? txns)
                   (not (too-frequent? txns :quarterly)))
          {:cadence :quarterly :month-residue residue})
        (when-let [cal-month (yearly-cadence? txns)]
          (when (and (stable-day? txns)
                     (not (too-frequent? txns :yearly)))
            {:cadence :yearly :month-of-year cal-month}))))))

(defn- due-this-month?
  [cadence year month]
  (case (:cadence cadence)
    :monthly true
    :quarterly (= (mod month 3) (:month-residue cadence))
    :yearly (= month (:month-of-year cadence))
    false))

(defn- preferred-shift
  "Look at months where the median day was not a business day and see whether
   the payment landed before or after it. Defaults to moving forward."
  [txns nominal-day]
  (let [shifts (keep (fn [txn]
                       (let [date (ms->local-date (:date txn))
                             nominal (local-date (.getYear date) (.getMonthValue date) nominal-day)]
                         (when-not (business-day? nominal)
                           (cond
                             (.isBefore date nominal) :backward
                             (.isAfter date nominal) :forward
                             :else nil))))
                     txns)
        backs (count (filter #{:backward} shifts))
        fwds (count (filter #{:forward} shifts))]
    (if (> backs fwds) :backward :forward)))

(defn- latest-amount
  [txns]
  (Math/abs (double (:amount (apply max-key :date txns)))))

(defn- series->payment
  [description txns cadence category-id year month match-texts source loan-id]
  (when (due-this-month? cadence year month)
    (let [nominal (int (Math/round (double (median (map day-of-month txns)))))
          direction (preferred-shift txns nominal)]
      {:description description
       :normalised description
       :expected-day (adjust-day year month nominal direction)
       :expected-amount (latest-amount txns)
       :cadence (name (:cadence cadence))
       :occurrences (count txns)
       :source source
       :category-id (str category-id)
       :match-texts (vec match-texts)
       :loan-id loan-id})))

(defn- recurring-candidates
  "Either the whole description is one series (so a modest price change still
   counts), or it splits into amount clusters when two bills share a name."
  [txns target-year target-month]
  (let [labelled (map #(assoc % :norm (normalise-description (:description %))) txns)
        groups (group-by :norm labelled)]
    (mapcat
     (fn [[desc group]]
       (if (str/blank? desc)
         []
         (if-let [cadence (and (similar-amounts? group)
                               (detect-cadence group target-year target-month))]
           [{:description desc :txns group :cadence cadence}]
           (->> (cluster-by-amount (sort-by #(Math/abs (double (:amount %))) group))
                (keep (fn [cluster]
                        (when-let [cadence (detect-cadence cluster target-year target-month)]
                          {:description desc :txns cluster :cadence cadence})))))))
     groups)))

(defn- txn-key
  [txn]
  [(:date txn) (:amount txn) (:description txn) (or (:date-index txn) 0)])

(defn- loan-series
  "Loan payments are matched with the same description filters the loan page
   uses. Each loan claims its transactions so a later loan, or the generic
   detector, cannot count them again."
  [loans txns category-id year month]
  (let [cat-txns (filterv #(and (= (str (:category-id %)) (str category-id))
                                (neg? (double (:amount %))))
                          txns)]
    (:series
     (reduce
      (fn [{:keys [claimed series]} loan]
        (let [texts (loans/loan-filter-texts loan)
              matched (when (seq texts)
                        (filterv (fn [txn]
                                   (and (not (contains? claimed (txn-key txn)))
                                        (some #(category-match/match-fun (:description txn) %) texts)))
                                 cat-txns))]
          (if (and (seq matched)
                   (stable-day? matched)
                   (not (too-frequent? matched :monthly)))
            {:claimed (into claimed (map txn-key matched))
             :series (conj series {:txns matched
                                   :payment (series->payment (or (:name loan) "Lån")
                                                             matched
                                                             {:cadence :monthly}
                                                             category-id
                                                             year
                                                             month
                                                             texts
                                                             "loan"
                                                             (some-> (:id loan) str))})}
            {:claimed claimed :series series})))
      {:claimed #{} :series []}
      loans))))

;; ---------------------------------------------------------------------------
;; Variable spending
;; ---------------------------------------------------------------------------

(defn- round-kr
  [x]
  (double (Math/round (double x))))

(defn- expense-amount
  "Spending as a positive number. Refunds (positive amounts) reduce it."
  [txn]
  (- (double (:amount txn))))

(defn- daily-amounts
  [txns year month]
  (let [n (days-in-month year month)
        by-day (group-by day-of-month txns)]
    (mapv (fn [day]
            (reduce + 0.0 (map expense-amount (get by-day day))))
          (range 1 (inc n)))))

(defn cumulative-fractions
  "Normalised cumulative curve for one month. Nil when the month nets to nothing."
  [amounts]
  (let [running (vec (reductions + amounts))
        total (peek running)]
    (when (and total (> total 0.5))
      (mapv #(double (/ % total)) running))))

(defn fraction-at
  [fractions day-fraction]
  (let [n (count fractions)
        idx (-> (* (double day-fraction) n)
                Math/ceil
                int
                dec
                (max 0)
                (min (dec n)))]
    (double (nth fractions idx))))

(defn- recency-weight
  [months-ago]
  (Math/exp (* (- recency-lambda) months-ago)))

(defn estimated-total
  "Recency-weighted total. Small samples use the median. Larger samples drop the
   lowest and highest month, then weight the rest toward recent months.
   `totals` is a vector of {:months-ago :total}, newest or oldest, order free."
  [totals]
  (let [n (count totals)]
    (cond
      (zero? n) nil
      (< n 5) (median (map :total totals))
      :else
      (let [sorted (vec (sort-by :total totals))
            kept (subvec sorted 1 (dec n))
            weighted (map (fn [{:keys [months-ago total]}]
                            (let [w (recency-weight months-ago)]
                              {:w w :wx (* w total)}))
                          kept)
            den (reduce + 0.0 (map :w weighted))]
        (when (pos? den)
          (/ (reduce + 0.0 (map :wx weighted)) den))))))

(defn- linear-shape
  [days]
  (mapv (fn [day] (/ (double day) days)) (range 1 (inc days))))

(defn- average-shape
  [samples days]
  (let [den (reduce + 0.0 (map :weight samples))]
    (if (pos? den)
      (mapv (fn [day]
              (let [fraction (/ (double day) days)
                    num (reduce + 0.0
                                (map (fn [{:keys [fractions weight]}]
                                       (* weight (fraction-at fractions fraction)))
                                     samples))]
                (/ num den)))
            (range 1 (inc days)))
      (linear-shape days))))

(defn- recurring-cumulative
  [payments days]
  (mapv (fn [day]
          (reduce + 0.0
                  (map (fn [payment]
                         (if (<= (:expected-day payment) day)
                           (:expected-amount payment)
                           0.0))
                       payments)))
        (range 1 (inc days))))

(defn- pointwise
  [f a b]
  (mapv f a b))

;; ---------------------------------------------------------------------------
;; One category
;; ---------------------------------------------------------------------------

(defn- categorised-id?
  [category-id]
  (and (some? category-id)
       (not (contains? excluded-category-ids (str category-id)))))

(defn- budget-of
  [category]
  (let [t (:target category)]
    (cond
      (number? t) (when (pos? t) (double t))
      (string? t) (let [n (try (Double/parseDouble t) (catch Exception _ nil))]
                    (when (and n (pos? n)) n))
      :else nil)))

(defn- recurring-only-months
  "Months whose category transactions were all claimed by a recurring series.
   Those months are not variable zeros: the series already accounts for them."
  [history-txns variable-txns]
  (let [variable-months (set (map txn-month-key variable-txns))]
    (set (remove variable-months (map txn-month-key history-txns)))))

(defn- variable-month-stats
  [variable-txns recurring-only active-months first-key target-year target-month]
  (let [last-12 (previous-month-keys target-year target-month shape-months)
        by-month (group-by txn-month-key variable-txns)]
    (keep
     (fn [key]
       (when (and (contains? active-months key)
                  (not (contains? recurring-only key))
                  (not (neg? (compare key first-key))))
         (let [[y m] (parse-month-key key)
               txns (get by-month key [])
               amounts (daily-amounts txns y m)
               total (reduce + 0.0 amounts)]
           {:key key
            :months-ago (months-between-keys key (format "%04d-%02d" target-year target-month))
            :total total
            :amounts amounts
            :fractions (cumulative-fractions amounts)})))
     last-12)))

(defn compute-category
  "Prediction for one category over `year`/`month`, from history that ends
   before that month. `history-txns` are already restricted to this category."
  [{:keys [category history-txns loans active-months year month]}]
  (let [category-id (:id category)
        expenses (filterv #(neg? (double (:amount %))) history-txns)
        loans-found (loan-series loans history-txns category-id year month)
        loan-keys (set (mapcat (fn [{:keys [txns]}] (map txn-key txns)) loans-found))
        loan-recs (mapv :payment loans-found)
        remaining (remove #(contains? loan-keys (txn-key %)) expenses)
        ;; A detected series that is not due this month still has to leave the
        ;; variable pool, otherwise a yearly bill inflates a random month.
        detected-all (recurring-candidates remaining year month)
        detected (keep (fn [{:keys [description txns cadence]}]
                         (series->payment description txns cadence category-id
                                          year month [] "detected" nil))
                       detected-all)
        recurring-keys (set (mapcat (fn [{:keys [txns]}] (map txn-key txns)) detected-all))
        payments (vec (concat loan-recs detected))
        variable (remove #(or (contains? loan-keys (txn-key %))
                              (contains? recurring-keys (txn-key %)))
                         history-txns)
        first-key (when (seq history-txns)
                    (first (sort (map txn-month-key history-txns))))
        recurring-found? (or (seq detected-all) (seq loans-found))
        stats (if first-key
                (variable-month-stats variable
                                      (recurring-only-months history-txns variable)
                                      active-months first-key year month)
                [])
        totals (mapv #(select-keys % [:months-ago :total]) stats)
        estimate (estimated-total totals)
        budget (budget-of category)
        n (count stats)
        cv (coefficient-of-variation (map :total stats))
        days (days-in-month year month)
        shape-samples (keep (fn [stat]
                              (when-let [fractions (:fractions stat)]
                                {:fractions fractions
                                 :weight (recency-weight (:months-ago stat))}))
                            stats)
        straight? (or (< n min-months-for-shape)
                      (> cv chaotic-cv)
                      (empty? shape-samples))
        ;; A thin or chaotic history uses a straight line of the robust total.
        ;; A budgeted category with no history at all falls back to its target.
        ;; A bill that is not due this month does not get that budget line.
        line-total (cond
                     (and straight? estimate (pos? (double estimate))) estimate
                     (and straight? budget (zero? n) (not recurring-found?)) budget
                     straight? (or estimate 0.0)
                     :else (or estimate 0.0))
        shape (if (and (not straight?) (seq shape-samples))
                (average-shape shape-samples days)
                (linear-shape days))
        variable-curve (if (and straight? (not (pos? line-total)) (empty? payments))
                         nil
                         (mapv #(double (* (if straight? line-total (or estimate 0.0)) %)) shape))
        recurring-curve (recurring-cumulative payments days)
        daily (when (or (seq payments) (and variable-curve (some pos? variable-curve)))
                (mapv round-kr (pointwise + recurring-curve (or variable-curve (repeat days 0.0)))))
        band (when (and daily
                        (not straight?)
                        (>= (count shape-samples) min-months-for-band)
                        (> cv band-cv))
               (let [abs-curves
                     (mapv (fn [stat]
                             (let [fractions (or (:fractions stat)
                                                (linear-shape (count (:amounts stat))))
                                   total (:total stat)]
                               (mapv (fn [day]
                                       (* total (fraction-at fractions (/ (double day) days))))
                                     (range 1 (inc days)))))
                           (filter #(pos? (:total %)) stats))]
                 (when (>= (count abs-curves) min-months-for-band)
                   {:low (mapv (fn [day]
                                 (round-kr (+ (nth recurring-curve (dec day))
                                              (percentile (map #(nth % (dec day)) abs-curves) 0.25))))
                               (range 1 (inc days)))
                    :high (mapv (fn [day]
                                  (round-kr (+ (nth recurring-curve (dec day))
                                               (percentile (map #(nth % (dec day)) abs-curves) 0.75))))
                                (range 1 (inc days)))})))]
    (when daily
      (cond-> {:category-id (str category-id)
               :method (cond
                         (and (seq payments) straight?) "recurring-plus-line"
                         (seq payments) "recurring-plus-variable"
                         straight? "straight-line"
                         :else "variable")
               :straight-line? (boolean straight?)
               :estimated-total (round-kr (or estimate line-total 0.0))
               :daily daily
               :recurring (mapv #(dissoc % :category-id) payments)}
        (:low band) (assoc :band-low (:low band) :band-high (:high band))))))

(defn compute-prediction
  "Build the frozen baseline for `year`/`month`.

   `transactions` may include the target month; those rows are ignored here and
   used only by `annotate-paid`. History for the shape is the previous 12
   months. Yearly and quarterly detection looks back `lookback-months`."
  [{:keys [transactions categories loans year month]}]
  (let [target-key (format "%04d-%02d" year month)
        history (filterv (fn [txn]
                           (neg? (compare (txn-month-key txn) target-key)))
                         transactions)
        active-months (set (map txn-month-key transactions))
        by-category (group-by #(str (:category-id %)) history)
        cats (->> categories
                  (filter #(categorised-id? (:id %)))
                  (keep (fn [category]
                          (compute-category
                           {:category category
                            :history-txns (get by-category (str (:id category)) [])
                            :loans loans
                            :active-months active-months
                            :year year
                            :month month})))
                  (sort-by :category-id)
                  vec)
        newest (when (seq history) (apply max (map :date history)))]
    {:month target-key
     :algorithm-version algorithm-version
     :newest-transaction-date (when newest
                                (str (ms->local-date newest)))
     :days-in-month (days-in-month year month)
     :baseline-frozen true
     :categories cats}))

(defn- payment-paid?
  [payment category-id month-txns]
  (boolean
   (some (fn [txn]
           (and (= (str (:category-id txn)) (str category-id))
                (neg? (double (:amount txn)))
                (amounts-close? (:amount txn) (:expected-amount payment))
                (if (seq (:match-texts payment))
                  (some #(category-match/match-fun (:description txn) %) (:match-texts payment))
                  (= (normalise-description (:description txn)) (:normalised payment)))))
         month-txns)))

(defn annotate-paid
  "Mark recurring payments paid when a matching expense has already landed in
   the predicted month. Does not change the cumulative curve."
  [prediction month-txns]
  (update prediction :categories
          (fn [cats]
            (mapv (fn [cat]
                    (update cat :recurring
                            (fn [payments]
                              (mapv #(assoc % :paid? (payment-paid? % (:category-id cat) month-txns))
                                    payments))))
                  cats))))

;; ---------------------------------------------------------------------------
;; Storage and HTTP
;; ---------------------------------------------------------------------------

(defn- now-iso []
  (str (Instant/now)))

(defn- history-start-iso
  [year month]
  (str (.minusMonths (LocalDate/of year month 1) (inc lookback-months))))

(defn load-or-compute
  "Return the stored baseline for this user and month, computing it on the first
   request. Paid flags are always derived from the transactions already in that
   month. A changed algorithm version replaces the baseline."
  [user-id year month]
  (let [month-key (format "%04d-%02d" year month)
        stored (db2/get-pace-prediction user-id month-key)
        baseline (if (and stored (= (:algorithm-version stored) algorithm-version))
                   stored
                   (let [txns (db2/get-transactions-after user-id (history-start-iso year month) true)
                         categories (category-svc/get-categories-with-filters user-id)
                         loans (db2/get-loans user-id)
                         prediction (-> (compute-prediction {:transactions txns
                                                             :categories categories
                                                             :loans loans
                                                             :year year
                                                             :month month})
                                        (assoc :created-at (now-iso)))]
                     (println "Computing pace prediction for" user-id month-key
                              "categories:" (count (:categories prediction)))
                     (db2/store-pace-prediction user-id prediction)
                     prediction))
        ;; The transaction query is exclusive of the start key, so begin the day
        ;; before the month in order to keep payments that land on the 1st.
        month-txns (filterv #(= (txn-month-key %) month-key)
                            (db2/get-transactions-after user-id
                                                       (str (.minusDays (LocalDate/of year month 1) 1))
                                                       true))]
    (annotate-paid baseline month-txns)))

(defn- request-month
  [request]
  (or (parse-month-key (get-in request [:params :month]))
      (parse-month-key (when-let [qs (:query-string request)]
                         (some (fn [pair]
                                 (let [[k v] (str/split pair #"=" 2)]
                                   (when (= k "month") (java.net.URLDecoder/decode (or v "") "UTF-8"))))
                               (str/split qs #"&"))))
      (let [today (LocalDate/now oslo)]
        [(.getYear today) (.getMonthValue today)])))

(defn prediction-handler
  [request]
  (try
    (let [user-id (:user-id request)
          month (request-month request)]
      (if-not (and (vector? month) (= 2 (count month)))
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error "month must be YYYY-MM"})}
        (let [[year m] month
              prediction (load-or-compute user-id year m)]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/write-str prediction)})))
    (catch Exception e
      (println "pace prediction failed:" (.getMessage e))
      (.printStackTrace e)
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "Could not build pace prediction"})})))

(defn payload->prediction
  "Read a stored EDN payload back into a prediction map."
  [payload]
  (when (and payload (not= payload ""))
    (edn/read-string payload)))
