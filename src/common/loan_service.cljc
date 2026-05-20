(ns common.loan-service)

(defn monthly-rate
  "Convert nominal annual rate to monthly rate."
  [nominal-annual-rate]
  (/ nominal-annual-rate 12.0))

(defn- days-in-month
  [year month]
  (let [days [31 28 31 30 31 30 31 31 30 31 30 31]
        leap? (and (zero? (mod year 4))
                   (or (not (zero? (mod year 100)))
                       (zero? (mod year 400))))]
    (if (and leap? (= month 2))
      29
      (nth days (dec month)))))

(defn- prev-month [year month]
  (if (= month 1) [(dec year) 12] [year (dec month)]))

(defn- next-month [year month]
  (if (= month 12) [(inc year) 1] [year (inc month)]))

(defn- current-year-month []
  #?(:cljs (let [d (js/Date.)] [(.getFullYear d) (inc (.getMonth d))])
     :clj  (let [d (java.time.LocalDate/now)]
             [(.getYear d) (.getMonthValue d)])))

(defn- current-ms []
  #?(:cljs (.getTime (js/Date.))
     :clj  (System/currentTimeMillis)))

(defn- unix-ms->year-month [ms]
  #?(:cljs (let [d (js/Date. ms)] [(.getFullYear d) (inc (.getMonth d))])
     :clj  (let [i  (java.time.Instant/ofEpochMilli (long ms))
                 ld (.atZone i (java.time.ZoneId/systemDefault))]
             [(.getYear ld) (.getMonthValue ld)])))

(defn- normalize-method
  "Accepts keyword or string; returns :actual or :simple."
  [m]
  (if (= :actual (some-> m name keyword)) :actual :simple))

(defn forward-balance
  "Closed-form forward walk: balance after n months under monthly rate r and per-month
   principal+interest payment p (fee excluded). Equivalent to running the amortization
   loop with no fee for n iterations."
  [B-start r p n]
  (if (zero? r)
    (- B-start (* p n))
    (let [f (Math/pow (+ 1.0 r) n)]
      (- (* B-start f) (* p (/ (- f 1.0) r))))))

(defn walk-back
  "Closed-form backward walk: balance n months earlier, given balance now (B-end),
   monthly rate r, and per-month principal+interest payment p."
  [B-end r p n]
  (if (zero? r)
    (+ B-end (* p n))
    (let [f (Math/pow (+ 1.0 r) n)]
      (/ (+ B-end (* p (/ (- f 1.0) r))) f))))

(defn solve-months
  "Closed-form remaining months for an annuity (B, p, r). Returns nil when p does
   not cover the current month's interest, since the loan would never amortize."
  [B p r]
  (when (and (pos? p) (pos? r) (> p (* B r)))
    (/ (- (Math/log (- 1.0 (/ (* B r) p))))
       (Math/log (+ 1.0 r)))))

(defn- bisect
  "Bisection on [lo, hi] for a root of f. Assumes f(lo) and f(hi) have opposite signs."
  [f lo hi iters eps]
  (loop [a lo b hi i 0]
    (let [m (/ (+ a b) 2.0)]
      (if (or (>= i iters) (< (- b a) eps))
        m
        (if (neg? (* (f a) (f m)))
          (recur a m (inc i))
          (recur m b (inc i)))))))

(defn solve-rate-for-payment
  "Find monthly rate r such that annuity(B, r, n) = p, via bisection on [0, 1]."
  [B p n]
  (let [annuity (fn [r]
                  (if (zero? r)
                    (/ B n)
                    (let [f (Math/pow (+ 1.0 r) n)]
                      (/ (* B r f) (- f 1.0)))))]
    (bisect #(- (annuity %) p) 0.0 1.0 60 1e-12)))

(defn solve-rate-for-end-balance
  "Find monthly rate r such that forward-balance(B-start, r, p, n) = B-end.
   Used to validate the initial segment's rate against :original-amount."
  [B-start B-end p n]
  (bisect #(- (forward-balance B-start % p n) B-end) 0.0 1.0 60 1e-12))

(defn extract-changes
  "Given a transaction list (each with :date as unix ms and :amount), return a vector
   of change records [{:date :prev-amount :new-amount :diff-pct} ...] for every
   consecutive pair where the absolute amount differs.

   Uses absolute amounts since loan payments are negative in this codebase."
  [txns]
  (let [sorted (sort-by :date txns)]
    (vec
     (keep (fn [[a b]]
             (let [pa (Math/abs (:amount a))
                   pb (Math/abs (:amount b))]
               (when (not= pa pb)
                 {:date (:date b)
                  :prev-amount pa
                  :new-amount pb
                  :diff-pct (/ (- pb pa) pa)})))
           (map vector sorted (rest sorted))))))

(defn classify-change
  "Classify a change as :interest (rate change → bank adjusts payment) or :payment
   (user voluntarily changed payment). Default threshold is 6% relative change."
  ([change] (classify-change change 0.06))
  ([{:keys [diff-pct]} threshold]
   (if (< (Math/abs diff-pct) threshold) :interest :payment)))

(defn months-between
  "Approximate integer number of monthly periods between two unix-ms timestamps."
  [t1 t2]
  (max 1 (Math/round (/ (- t2 t1) (* 1000.0 86400.0 30.4375)))))

(defn- build-segments
  "Construct segments from sorted transactions + classifications. Each segment carries
   :start-date, :end-date, :amount (abs kr), :type (:initial | :interest | :payment)."
  [txns classifications threshold today-ms]
  (let [sorted (sort-by :date txns)
        first-tx (first sorted)
        changes (extract-changes sorted)
        segs (vec
              (cons {:start-date (:date first-tx)
                     :amount (Math/abs (:amount first-tx))
                     :type :initial}
                    (map-indexed
                     (fn [i ch]
                       {:start-date (:date ch)
                        :amount (:new-amount ch)
                        :type (or (get classifications i)
                                  (classify-change ch threshold))})
                     changes)))]
    (mapv (fn [seg next-seg]
            (assoc seg :end-date (if next-seg
                                   (:start-date next-seg)
                                   today-ms)))
          segs
          (concat (rest segs) [nil]))))

(defn derive-history
  "Walk newest → oldest through segments derived from transactions, computing each
   segment's monthly rate (stored as annual) and start-balance from local annuity
   constraints.

   Inputs:
   - loan map with :nominal-rate, :monthly-payment, :monthly-fee, :balance,
     optional :original-amount
   - txns: transactions matching the loan-tagged filter (each at least {:date :amount})
   - classifications: map change-index → :interest|:payment (user overrides; missing
     entries fall back to classify-change with the given threshold)
   - threshold: relative-diff threshold for auto-classification (e.g. 0.03)
   - today-ms: current time in unix ms

   Returns {:segments [...] :discrepancy <number|nil>} where each segment carries
   :date :amount :type :rate (annual) :start-balance, chronologically ordered.
   :discrepancy is the kr difference between the derived first-segment start-balance
   and :original-amount when both are known — non-nil signals a mismatch."
  [{:keys [nominal-rate monthly-payment monthly-fee balance original-amount]}
   txns classifications threshold today-ms]
  (let [fee (or monthly-fee 0)
        segs (build-segments txns classifications threshold today-ms)
        n (count segs)]
    (when (pos? n)
      (let [last-idx (dec n)
            last-seg (nth segs last-idx)
            r-last (/ nominal-rate 12.0)
            p-last (- monthly-payment fee)
            n-last (months-between (:start-date last-seg) (:end-date last-seg))
            b-start-last (walk-back balance r-last p-last n-last)
            seed (assoc last-seg :rate nominal-rate :start-balance b-start-last)
            walked (reduce
                    (fn [acc i]
                      (let [seg (nth segs i)
                            next-rec (peek acc)
                            r-next-monthly (/ (:rate next-rec) 12.0)
                            p-next (- (:amount next-rec) fee)
                            b-change (:start-balance next-rec)
                            p-this (- (:amount seg) fee)
                            next-type (:type next-rec)
                            r-this-monthly
                            (case next-type
                              :interest
                              (let [n-target (solve-months b-change p-next r-next-monthly)]
                                (if (and n-target (pos? n-target))
                                  (solve-rate-for-payment b-change p-this n-target)
                                  r-next-monthly))
                              :payment r-next-monthly
                              r-next-monthly)
                            n-this (months-between (:start-date seg) (:end-date seg))
                            b-start-this (walk-back b-change r-this-monthly p-this n-this)]
                        (conj acc (assoc seg
                                         :rate (* r-this-monthly 12.0)
                                         :start-balance b-start-this))))
                    [seed]
                    (reverse (range last-idx)))
            chronological (vec (reverse walked))
            history (mapv (fn [s]
                            {:date (:start-date s)
                             :amount (:amount s)
                             :type (:type s)
                             :rate (:rate s)
                             :start-balance (:start-balance s)})
                          chronological)
            discrepancy (when (and original-amount (seq history))
                          (- (:start-balance (first history)) original-amount))]
        {:segments history
         :discrepancy discrepancy}))))

(declare amortization-schedule)

(defn extend-payment-history
  "Incremental, append-only refresh of a loan's :payment-history.

   Walks forward from the last segment of the existing history using only the
   newly-arrived matching transactions. Detects new change-points by comparing
   each new transaction's absolute amount to the carrying segment amount; for
   each change, derives a new segment locally (preserved-payoff-date rule for
   interest changes, rate carries for payment changes).

   Also updates the loan's top-level :nominal-rate and :monthly-payment to match
   the latest segment after extension. :balance is left untouched (it comes from
   the bank import path, not from derivation).

   Returns the (possibly unchanged) loan."
  [{:keys [payment-history monthly-fee interest-method] :as loan} new-matching-txns]
  (let [fee (or monthly-fee 0)
        balance-after-n
        ;; Iterated, whole-kr amortization walk for the first n months. Matches the
        ;; bank's actual rounded trajectory rather than the closed-form annuity, so
        ;; the balance handed to solve-rate-for-payment is the same balance the
        ;; bank had at the change point.
        (fn [seg n]
          (let [[sy sm] (unix-ms->year-month (:date seg))
                rows (amortization-schedule
                      (:start-balance seg)
                      (/ (:rate seg) 12.0)
                      (- (:amount seg) fee)
                      {:interest-method interest-method
                       :nominal-rate (:rate seg)
                       :start-year sy
                       :start-month sm})]
            (or (some-> (nth rows (dec n) nil) :remaining)
                (some-> (last rows) :remaining)
                (:start-balance seg))))]
    (if (or (empty? payment-history) (empty? new-matching-txns))
      loan
      (let [last-seg-date (:date (peek payment-history))
            sorted-new (->> new-matching-txns
                            (filter #(> (:date %) last-seg-date))
                            (sort-by :date))
            extended
            (reduce
             (fn [hist tx]
               (let [seg (peek hist)
                     seg-amt (:amount seg)
                     tx-amt (Math/abs (:amount tx))]
                 (if (== seg-amt tx-amt)
                   hist
                   (let [seg-r-monthly (/ (:rate seg) 12.0)
                         seg-p (- seg-amt fee)
                         n (months-between (:date seg) (:date tx))
                         b-at-change (balance-after-n seg n)
                         new-p (- tx-amt fee)
                         diff-pct (/ (- tx-amt seg-amt) seg-amt)
                         t (classify-change {:diff-pct diff-pct})
                         new-r-monthly
                         (case t
                           :interest
                           (let [n-target (solve-months b-at-change seg-p seg-r-monthly)]
                             (if (and n-target (pos? n-target))
                               (solve-rate-for-payment b-at-change new-p n-target)
                               seg-r-monthly))
                           :payment seg-r-monthly)]
                     (conj hist {:date (:date tx)
                                 :amount tx-amt
                                 :type (name t)
                                 :rate (* new-r-monthly 12.0)
                                 :start-balance b-at-change})))))
             payment-history
             sorted-new)]
        (if (= extended payment-history)
          loan
          (let [latest (peek extended)]
            (assoc loan
                   :payment-history extended
                   :nominal-rate (:rate latest)
                   :monthly-payment (:amount latest))))))))

(defn- round-kr [x]
  (double (Math/round x)))

(defn amortization-schedule
  "Compute month-by-month amortization from a starting balance.
   Returns [{:month 1 :interest X :principal Y :remaining Z} ...].

   Each row's interest, principal, and resulting balance are rounded to whole
   kroner before being carried forward, matching the bank's per-month rounding
   convention. This keeps the trajectory exactly aligned with bank statements
   instead of drifting on the øre/float boundary.

   opts:
     :interest-method  :simple (default, nominal/12 each month)
                       :actual (nominal × days-in-prior-month / 365)
     :nominal-rate     required when :actual
     :start-year/month payment month of the first row; required when :actual"
  ([balance r-monthly payment]
   (amortization-schedule balance r-monthly payment {}))
  ([balance r-monthly payment {:keys [interest-method nominal-rate start-year start-month]}]
   (let [method (normalize-method interest-method)
         max-months 1200]
     (loop [bal balance
            month 1
            y start-year
            m start-month
            result []]
       (if (or (<= bal 0.01) (> month max-months))
         result
         (let [raw-interest (if (= method :actual)
                              (let [[py pm] (prev-month y m)
                                    days (days-in-month py pm)]
                                (* bal nominal-rate (/ days 365.0)))
                              (* bal r-monthly))
               interest (round-kr raw-interest)
               principal (min bal (round-kr (- payment interest)))
               new-bal (round-kr (- bal principal))
               [ny nm] (if (= method :actual) (next-month y m) [y m])]
           (recur new-bal
                  (inc month)
                  ny nm
                  (conj result {:month month
                                :interest interest
                                :principal principal
                                :remaining new-bal}))))))))

(defn walk-paid-schedule
  "Build the actual paid schedule by concatenating per-segment amortization rows
   from :payment-history. Each segment uses its stored :rate and :start-balance
   over its calendar duration (or until today for the last segment)."
  [{:keys [monthly-fee payment-history interest-method]} today-ms]
  (let [fee (or monthly-fee 0)
        segs (vec payment-history)
        n (count segs)]
    (when (pos? n)
      (loop [i 0
             month 0
             result []]
        (if (>= i n)
          result
          (let [seg        (nth segs i)
                next-start (if (< (inc i) n)
                             (:date (nth segs (inc i)))
                             today-ms)
                seg-months (months-between (:date seg) next-start)
                seg-rate   (:rate seg)
                r-m        (/ seg-rate 12.0)
                p          (- (:amount seg) fee)
                b          (:start-balance seg)
                [sy sm]    (unix-ms->year-month (:date seg))
                rows       (take seg-months
                                 (amortization-schedule b r-m p
                                                        {:interest-method interest-method
                                                         :nominal-rate    seg-rate
                                                         :start-year      sy
                                                         :start-month     sm}))
                renumbered (map-indexed (fn [j r] (assoc r :month (+ month j 1)))
                                        rows)]
            (recur (inc i)
                   (+ month (count renumbered))
                   (into result renumbered))))))))

(defn loan-summary
  "Compute full loan summary from a loan map.
   Returns map with :schedule, :remaining-principal, :remaining-interest,
   :paid-principal, :paid-interest, :remaining-fees, :paid-fees
   (paid fields when :payment-history or :original-amount is given)."
  [{:keys [nominal-rate balance monthly-payment monthly-fee original-amount
           interest-method payment-history] :as loan}]
  (let [r-m (monthly-rate nominal-rate)
        fee (or monthly-fee 0)
        payment (- monthly-payment fee)
        [start-year start-month] (current-year-month)
        sched-opts {:interest-method interest-method
                    :nominal-rate nominal-rate
                    :start-year start-year
                    :start-month start-month}
        schedule (amortization-schedule balance r-m payment sched-opts)
        remaining-interest (reduce + 0 (map :interest schedule))
        remaining-fees (* fee (count schedule))
        remaining-principal balance
        use-history? (and (seq payment-history)
                          (every? #(and (:start-balance %) (:rate %)) payment-history))
        paid-schedule
        (cond
          use-history?
          (walk-paid-schedule loan (current-ms))
          original-amount
          (let [full-schedule (amortization-schedule original-amount r-m payment sched-opts)
                payments-made (- (count full-schedule) (count schedule))]
            (vec (take payments-made full-schedule))))
        effective-original
        (cond
          use-history?     (:start-balance (first payment-history))
          original-amount  original-amount
          :else            nil)
        paid-principal  (when effective-original (- effective-original balance))
        paid-interest   (when paid-schedule
                          (reduce + 0 (map :interest paid-schedule)))
        paid-fees       (when paid-schedule (* fee (count paid-schedule)))]
    (cond-> {:schedule schedule
             :remaining-principal remaining-principal
             :remaining-interest remaining-interest
             :remaining-fees remaining-fees
             :remaining-months (count schedule)
             :monthly-fee fee
             :total-remaining (+ remaining-principal remaining-interest remaining-fees)}
      effective-original (assoc :paid-principal paid-principal
                                :paid-interest paid-interest
                                :paid-fees paid-fees
                                :paid-schedule paid-schedule
                                :original-amount effective-original
                                :total-cost (+ effective-original
                                               (or paid-interest 0)
                                               (or paid-fees 0)
                                               remaining-interest
                                               remaining-fees)))))
