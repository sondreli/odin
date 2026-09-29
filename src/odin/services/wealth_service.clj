(ns odin.services.wealth-service
  "Compute a portfolio value time-series with per-bar appreciation from imported
   investment transactions.

   Key ideas:
   - All amounts are in NOK (we use the broker's NOK :amount-nok, not native Kurs).
   - Stock splits are not adjusted in the Nordnet export, so we DERIVE them from the
     running holdings column (:total-quantity / \"Totalt antall\") and normalise every
     trade to the *current* share basis. After normalisation no further split handling
     is needed: FIFO works directly on normalised quantities/prices.
   - Phase 2 prices a holding at the most recent trade price (a step function). Phase 3
     will swap in real daily market prices."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [odin.db2 :as db2]
            [odin.services.price-service :as price])
  (:import [java.time Instant ZoneId YearMonth]))

(def ^:private oslo (ZoneId/of "Europe/Oslo"))

(defn- ms->local-date [ms]
  (-> (Instant/ofEpochMilli (long ms)) (.atZone oslo) (.toLocalDate)))

(defn- year-month [ms]
  (YearMonth/from (ms->local-date ms)))

(defn- ym-key [^YearMonth ym]
  (format "%04d-%02d" (.getYear ym) (.getMonthValue ym)))

(defn- ym-end-ms
  "Exclusive cutoff: first instant of the month AFTER ym (Oslo)."
  [^YearMonth ym]
  (-> (.plusMonths ym 1) (.atDay 1) (.atStartOfDay oslo) (.toInstant) (.toEpochMilli)))

(defn- months-range
  "Inclusive seq of YearMonths from start to end."
  [^YearMonth start ^YearMonth end]
  (loop [m start acc []]
    (if (.isAfter m end)
      acc
      (recur (.plusMonths m 1) (conj acc m)))))

;; ---------------------------------------------------------------------------
;; Split derivation
;; ---------------------------------------------------------------------------

(defn- signed-qty [{:keys [type quantity]}]
  (* (if (= "sell" type) -1.0 1.0) (or quantity 0.0)))

(defn- snap-factor
  "Snap a raw ratio to a clean split factor, or 1.0 if it's just intra-day noise.
   Forward splits snap to the nearest integer (2,3,5,...); reverse splits to 1/integer.
   Ratios within ~15% of 1.0 are treated as no split."
  [raw]
  (cond
    (or (nil? raw) (not (pos? raw))) 1.0
    (>= raw 1.5) (let [n (Math/round (double raw))]
                   (if (< (Math/abs (- raw n)) (* 0.08 n)) (double n) 1.0))
    (<= raw 0.67) (let [n (Math/round (/ 1.0 raw))]
                    (if (and (pos? n) (< (Math/abs (- (/ 1.0 raw) n)) (* 0.08 n)))
                      (/ 1.0 (double n))
                      1.0))
    :else 1.0))

(defn derive-splits
  "Given one ISIN's trades (each with :date, :date-index, :type, :quantity,
   :total-quantity), derive split events as [{:date ms :factor f} ...].
   Splits are detected from day-to-day jumps in end-of-day total holdings that are not
   explained by that day's net trading."
  [trades]
  (let [by-day (->> trades
                    (group-by :date)
                    (map (fn [[day ts]]
                           (let [ordered (sort-by :date-index ts)]
                             {:date day
                              :end-total (:total-quantity (last ordered))
                              :net (reduce + 0.0 (map signed-qty ts))})))
                    (sort-by :date))]
    (->> by-day
         (partition 2 1)
         (keep (fn [[prev cur]]
                 (let [prev-total (:end-total prev)
                       cur-total (:end-total cur)]
                   (when (and prev-total cur-total (pos? prev-total))
                     (let [raw (/ (- cur-total (:net cur)) prev-total)
                           f (snap-factor raw)]
                       (when (not= f 1.0)
                         {:date (:date cur) :factor f}))))))
         vec)))

(defn cumulative-factor
  "Product of split factors for splits occurring strictly AFTER date `ms`.
   Converts a quantity/price at `ms` into today's (post-all-splits) share basis."
  [splits ms]
  (reduce (fn [acc {:keys [date factor]}]
            (if (> date ms) (* acc factor) acc))
          1.0 splits))

;; ---------------------------------------------------------------------------
;; FIFO ledger (operates on split-normalised quantities/prices)
;; ---------------------------------------------------------------------------

(defn- normalise-trade
  "Express a trade in current share basis: more shares, proportionally lower per-share
   price. Per-share price uses NOK :amount-nok so it is FX-inclusive."
  [splits {:keys [date quantity amount-nok] :as trade}]
  (let [cf (cumulative-factor splits date)
        qty (* (or quantity 0.0) cf)
        raw-price (if (and amount-nok quantity (pos? quantity))
                    (/ (Math/abs (double amount-nok)) quantity)
                    0.0)]
    (assoc trade
           :norm-qty qty
           :norm-price (/ raw-price cf))))

(defn- consume-front
  "Remove `qty` shares from the front of the lots vector."
  [lots qty]
  (loop [lots (vec lots) remaining qty]
    (if (or (<= remaining 1e-9) (empty? lots))
      lots
      (let [{lq :qty :as lot} (first lots)]
        (if (<= lq (+ remaining 1e-9))
          (recur (subvec lots 1) (- remaining lq))
          (recur (assoc lots 0 (assoc lot :qty (- lq remaining))) 0.0))))))

(defn- consume-lots
  "Remove `qty` shares using the given matching method. Lots are chronological
   (oldest first), so :fifo consumes the front and :lifo the back."
  [lots qty method]
  (if (= method :lifo)
    (-> (vec (rseq (vec lots))) (consume-front qty) rseq vec)
    (consume-front (vec lots) qty)))

(defn- apply-trade [method {:keys [type norm-qty norm-price date] :as _trade} state]
  (let [state (assoc state :last-price norm-price)]
    (if (= "sell" type)
      (update state :lots consume-lots norm-qty method)
      (update state :lots conj {:qty norm-qty :cost norm-price
                                :cohort (ym-key (year-month date))}))))

(defn- reduce-state
  "Replay normalised trades into {:lots [{:qty :cost :cohort}] :last-price} using the
   FIFO/LIFO `method`. Lots are tagged with their purchase-month cohort by apply-trade."
  [norm-trades method]
  (reduce (fn [state trade] (apply-trade method trade state))
          {:lots [] :last-price 0.0}
          norm-trades))

;; ---------------------------------------------------------------------------
;; Series assembly
;; ---------------------------------------------------------------------------

(defn- asset-trades
  "Per-month buy/sell markers for one ISIN: [{:month :type :quantity :amount-nok} ...]
   aggregated by month+type (a month may have both a buy and a sell entry)."
  [trades]
  (->> trades
       (group-by (juxt #(ym-key (year-month (:date %))) :type))
       (map (fn [[[month type] ts]]
              {:month month
               :type type
               :quantity (reduce + 0.0 (map #(or (:quantity %) 0.0) ts))
               :amount-nok (reduce + 0.0 (map #(or (:amount-nok %) 0.0) ts))}))
       (sort-by :month)
       vec))

(defn- asset-data
  "Everything for one ISIN: monthly value :points, purchase-cohort :buy-bands
   (FIFO/LIFO-aware remaining value per month), counterfactual :sell-bands, and :trades.
   Bands are [{:cohort \"YYYY-MM\" :points [{:date :value} ...]}]."
  [isin trades months price-fn method]
  (let [splits (derive-splits trades)
        norm (->> trades
                  (map (partial normalise-trade splits))
                  (sort-by (juxt :date :date-index)))
        ;; per-month snapshot incl. cohort breakdown
        per-month (for [m months
                        :let [cutoff (ym-end-ms m)
                              upto (filter #(< (:date %) cutoff) norm)]
                        :when (seq upto)]
                    (let [{:keys [lots last-price]} (reduce-state upto method)
                          price (or (price-fn isin cutoff) last-price)
                          qty (reduce + 0.0 (map :qty lots))
                          cost-basis (reduce + 0.0 (map #(* (:qty %) (:cost %)) lots))
                          cohort-qty (reduce (fn [acc l]
                                               (update acc (:cohort l) (fnil + 0.0) (:qty l)))
                                             {} lots)]
                      {:month m :date (ym-key m) :price price
                       :value (* qty price) :cost-basis cost-basis
                       :cohort-value (into {} (map (fn [[c q]] [c (* q price)]) cohort-qty))}))
        points (mapv (fn [pm]
                       {:date (:date pm) :value (:value pm) :cost-basis (:cost-basis pm)
                        :appreciation (when (pos? (:cost-basis pm))
                                        (- (/ (:value pm) (:cost-basis pm)) 1.0))})
                     per-month)
        ;; buy-bands: pivot cohort-value per cohort into a month series (drop ~0 points)
        cohorts (->> per-month (mapcat (comp keys :cohort-value)) distinct sort)
        buy-bands (->> cohorts
                       (map (fn [c]
                              {:cohort c
                               :points (->> per-month
                                            (keep (fn [pm]
                                                    (let [v (get (:cohort-value pm) c)]
                                                      (when (and v (> v 1e-6))
                                                        {:date (:date pm) :value v}))))
                                            vec)}))
                       (remove #(empty? (:points %)))
                       vec)
        ;; sell-bands: counterfactual sold-qty(month) × price(M) for M >= sell month
        sell-qty (->> norm
                      (filter #(= "sell" (:type %)))
                      (group-by #(ym-key (year-month (:date %))))
                      (map (fn [[j ts]] [j (reduce + 0.0 (map :norm-qty ts))]))
                      (into {}))
        sell-bands (->> sell-qty
                        (map (fn [[j q]]
                               {:cohort j
                                :points (->> per-month
                                             (filter #(>= (compare (:date %) j) 0))
                                             (mapv (fn [pm] {:date (:date pm) :value (* q (:price pm))})))}))
                        (sort-by :cohort)
                        vec)]
    {:isin isin
     :name (:security-name (first trades))
     :points points
     :buy-bands buy-bands
     :sell-bands sell-bands
     :trades (asset-trades trades)}))

(defn- merge-bands
  "Merge per-asset bands by cohort+date, summing values (for the Total view)."
  [band-seqs]
  (->> (apply concat band-seqs)
       (group-by :cohort)
       (map (fn [[c bs]]
              {:cohort c
               :points (->> (mapcat :points bs)
                            (group-by :date)
                            (map (fn [[d ps]] {:date d :value (reduce + 0.0 (map :value ps))}))
                            (sort-by :date)
                            vec)}))
       (sort-by :cohort)
       vec))

(defn- merge-trades [trade-seqs]
  (->> (apply concat trade-seqs)
       (group-by (juxt :month :type))
       (map (fn [[[m t] xs]]
              {:month m :type t
               :quantity (reduce + 0.0 (map :quantity xs))
               :amount-nok (reduce + 0.0 (map :amount-nok xs))}))
       (sort-by :month)
       vec))

;; Nordnet dynamic belåningsgrad lookup table (nordnet.no/tjenester/verdipapirbelaning).
;; Rows = entered (base) belåningsgrad %; columns = the portfolio's LARGEST-position weight.
;; Value = effective belåningsgrad %. Diversified portfolios (left columns) earn a premium
;; above base; concentrated ones (right columns) are reduced. All values in percent.
(def ^:private leverage-col-weights [0.10 0.20 0.30 0.40 0.60 0.80]) ;; 0.60 represents the "50–70%" column
(def ^:private leverage-table
  ;; base%   @10  @20  @30  @40  @50-70  @80
  [[10       40   40   30   20   10      7]
   [20       45   45   35   30   20      9]
   [30       50   50   45   40   30      18]
   [40       60   55   50   45   40      28]
   [50       70   65   60   55   50      40]
   [60       80   75   70   65   60      52]
   [70       85   80   80   75   70      64]
   [80       85   85   85   80   80      75]
   [85       90   90   85   85   85      81]])

(defn- lerp [x x0 x1 y0 y1]
  (if (== x0 x1) y0 (+ y0 (* (- y1 y0) (/ (- x x0) (- x1 x0))))))

(defn- interp
  "Piecewise-linear interpolation of y over ascending breakpoints xs; clamps outside range."
  [x xs ys]
  (cond
    (<= x (first xs)) (first ys)
    (>= x (last xs)) (last ys)
    :else (loop [i 1]
            (if (<= x (nth xs i))
              (lerp x (nth xs (dec i)) (nth xs i) (nth ys (dec i)) (nth ys i))
              (recur (inc i))))))

(defn effective-ratio
  "Nordnet's dynamic effective belåningsgrad (0–1) for a security with entered ratio
   `base-frac` (0–1) when the portfolio's largest position is weight `largest-w` (0–1).
   Bilinear interpolation over the published table; the 50–70% band is flat (col-weight 0.60)."
  [base-frac largest-w]
  (let [base% (* 100.0 base-frac)
        bases (mapv first leverage-table)
        ;; effective % for each table row at this largest-weight
        row-effs (mapv (fn [row] (interp largest-w leverage-col-weights (subvec row 1))) leverage-table)]
    (/ (interp base% bases row-effs) 100.0)))

(defn build-wealth
  "Build the full wealth response from a user's investment transactions.
   `price-fn` is (fn [isin cutoff-ms] -> price-nok|nil); defaults to none (step pricing).
   `method` is :fifo (default) or :lifo — the lot-matching used for sells/cost basis.
   `ratios` is {isin -> collateral-fraction 0..1} for the leverage series."
  ([investment-txns] (build-wealth investment-txns (fn [_ _] nil) :fifo {}))
  ([investment-txns price-fn] (build-wealth investment-txns price-fn :fifo {}))
  ([investment-txns price-fn method] (build-wealth investment-txns price-fn method {}))
  ([investment-txns price-fn method ratios]
   (if (empty? investment-txns)
    {:total {:points [] :buy-bands [] :sell-bands [] :trades [] :leverage []} :assets []}
    (let [trade-txns (filter #(#{"buy" "sell"} (:type %)) investment-txns)
          by-isin (group-by :isin trade-txns)
          all-dates (map :date investment-txns)
          start (year-month (apply min all-dates))
          end (YearMonth/now oslo)
          months (months-range start end)
          assets (mapv (fn [[isin trades]] (asset-data isin trades months price-fn method))
                       by-isin)
          ;; Total points = sum of asset market values per month; appreciation = value-weighted.
          point-index (into {} (for [a assets]
                                  [(:isin a) (into {} (map (juxt :date identity) (:points a)))]))
          total-points (for [m months
                             :let [k (ym-key m)
                                   pts (keep #(get-in point-index [(:isin %) k]) assets)
                                   value (reduce + 0.0 (map :value pts))
                                   cost (reduce + 0.0 (map :cost-basis pts))]
                             :when (seq pts)]
                         {:date k
                          :value value
                          :appreciation (when (pos? cost) (- (/ value cost) 1.0))})
          ;; Leverage: max capacity = Σ holding value × collateral ratio; used = drawn margin.
          ;; Actual margin drawn is read from the credit-account (kredittkonto) balance —
          ;; imported separately as :type "credit" rows carrying Nordnet's running :balance
          ;; (Saldo). For each month we take the latest credit balance on/before month-end.
          ;; Accounts without a credit export fall back to the reconstructed cash balance.
          date-set (set (map :date total-points))
          credit-rows (->> investment-txns
                           (filter #(and (= "credit" (:type %)) (some? (:balance %))))
                           (sort-by (juxt :date :date-index)))
          credit-balance-at (fn [cutoff]
                              (->> credit-rows
                                   (filter #(< (:date %) cutoff))
                                   last
                                   :balance))
          leverage (->> months
                        (keep (fn [m]
                                (let [k (ym-key m)]
                                  (when (contains? date-set k)
                                    (let [cutoff (ym-end-ms m)
                                          cash (reduce + 0.0 (for [t investment-txns
                                                                   :when (and (:amount-nok t)
                                                                              (not= "credit" (:type t))
                                                                              (< (:date t) cutoff))]
                                                               (:amount-nok t)))
                                          credit-bal (credit-balance-at cutoff)
                                          used (if (some? credit-bal)
                                                 (max 0.0 (- credit-bal))
                                                 (- (min 0.0 cash)))
                                          vals (for [a assets
                                                     :let [p (get-in point-index [(:isin a) k])]
                                                     :when p]
                                                 {:v (:value p) :r (double (get ratios (:isin a) 0.0))})
                                          total (reduce + 0.0 (map :v vals))
                                          largest (reduce max 0.0 (map :v vals))
                                          conc (if (pos? total) (/ largest total) 0.0)
                                          raw-cap (reduce + 0.0 (map #(* (:v %) (:r %)) vals))
                                          ;; dynamic: each holding's effective ratio depends on
                                          ;; the portfolio's largest-position weight (Nordnet table)
                                          mx (reduce + 0.0 (map #(* (:v %) (effective-ratio (:r %) conc)) vals))]
                                      {:date k
                                       :max mx
                                       :used used
                                       :concentration conc
                                       :effective-rate (if (pos? total) (/ mx total) 0.0)})))))
                        vec)]
      {:total {:points (vec total-points)
               :buy-bands (merge-bands (map :buy-bands assets))
               :sell-bands (merge-bands (map :sell-bands assets))
               :trades (merge-trades (map :trades assets))
               :leverage leverage}
       :assets assets}))))

;; ---------------------------------------------------------------------------
;; Handler
;; ---------------------------------------------------------------------------

(defn- query-param
  "Read a single query-string parameter (works under both Jetty and the Lambda adapter)."
  [request k]
  (or (get-in request [:query-params k])
      (when-let [qs (:query-string request)]
        (some (fn [pair]
                (let [[pk pv] (str/split pair #"=" 2)]
                  (when (= pk k) pv)))
              (str/split qs #"&")))))

(defn wealth-handler [request]
  (let [user-id (:user-id request)
        method (if (= "lifo" (query-param request "method")) :lifo :fifo)
        txns (db2/get-investment-transactions user-id)
        price-fn (price/price-lookup-fn (distinct (keep :isin txns)))
        ratios (into {} (map (juxt :isin :pawn-percentage) (db2/get-security-settings user-id)))
        refreshed-ms (db2/get-prices-refreshed-at user-id)
        result (assoc (build-wealth txns price-fn method ratios)
                      :prices-updated-at (when refreshed-ms (str (Instant/ofEpochMilli refreshed-ms))))]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str result)}))

;; ---------------------------------------------------------------------------
;; Leverage (collateral ratio) settings
;; ---------------------------------------------------------------------------

(defn leverage-settings-handler [request]
  (let [user-id (:user-id request)
        settings (db2/get-security-settings user-id)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str (into {} (map (juxt :isin :pawn-percentage) settings)))}))

(defn save-leverage-setting-handler [request]
  (let [user-id (:user-id request)
        {:keys [isin pawn-percentage]} (:body request)
        ratio (cond
                (number? pawn-percentage) (double pawn-percentage)
                (string? pawn-percentage) (try (Double/parseDouble pawn-percentage) (catch Exception _ 0.0))
                :else 0.0)]
    (if (clojure.string/blank? isin)
      {:status 400 :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "isin required"})}
      (do
        (db2/put-security-setting user-id isin (-> ratio (max 0.0) (min 1.0)))
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:isin isin :pawn-percentage ratio})}))))
