# Smart Pace Prediction — Implementation Plan

**Feature**: Per-user, per-month predicted cumulative spending curve per category  
**Task Reference**: Tasks 7.1–7.6 in `treemap-budget-tasks.md`  
**Replaces**: Straight-line pace from tasks 3.4 and 4.1

---

## 1. Current Architecture Summary

### 1.1 Data Model (DynamoDB)

| Table | Keys | Description | Relevant Files |
|-------|------|-------------|----------------|
| `Transaction` | `UserId` (PK), `Timestamp` (SK) | Composite sortkey: `unixtime#dateindex` | `src/odin/db2.clj`, `src/odin/db_schemas2.clj` |
| `Category` | `UserId` (PK), `Id` (SK) | Has `:target` (budget), `:marker`, `:bucket` | `src/odin/db2.clj:168-177` |
| `Filter` | `UserId` (PK), `Id` (SK) | Text patterns for category matching | `src/odin/db2.clj:183-188` |
| `Loan` | `UserId` (PK), `Id` (SK) | Has `:filter-texts`, `:payment-history` | `src/odin/db2.clj:190-201` |

**Transaction schema** (`src/odin/db2.clj:116-126`):
```clojure
[:user-id :date :description :amount :category-id :marked-by-filter? 
 :source :tag-ids :filter-tag-ids :account-id]
```

### 1.2 Category and Filter Matching

- **Filter matching**: `src/common/category_service.cljc` — `match-fun` uses substring or `regex:` prefix patterns
- **Category assignment**: `add-categories` applies filters to transactions, sets `:category-id` and `:marked-by-filter?`
- **Backend service**: `src/odin/services/category_service.clj` — `get-categories-with-filters` joins categories with their filters

### 1.3 Loan Payment Matching (Reusable for Recurring Detection)

**Key file**: `src/common/loan_service.cljc`

The loan service already detects recurring loan payments:
- `extend-payment-history` (lines 226–301): Incremental detection of payment changes
- `extract-changes` (lines 99–116): Finds consecutive transactions with amount changes
- `classify-change` (lines 117–121): Distinguishes rate changes (<6% diff) from payment changes
- `months-between` (lines 123–127): Calculates months between timestamps
- `matching-txns-for-loan` in `src/odin/services/loan_service.clj:14-22`: Filters transactions by description patterns

**Reusable concepts**:
- Description pattern matching via `category-svc/match-fun`
- Amount tolerance comparison
- Month-based cadence detection

### 1.4 Budget and Treemap Data Flow

**Backend → Frontend**:
1. `GET /transactions` → `transaction_handler` (`src/odin/services/transaction_service.clj:500-540`)
2. `GET /categories` → `categories-handler` (`src/odin/services/category_service.clj:55-59`)

**Frontend state** (`src/client/subs.cljs`):
- `:summed-categories` — categories with aggregated period spending
- `:period-transactions` — transactions in current period
- `:period` — `{:start :end :period-type}`

**Treemap views**:
- Web: `src/client/components/treemap_component/views.cljs`
- Mobile: `src/client/mobile/views.cljs`
- Layout: `src/client/components/treemap_component/layout.cljs`

**Summary bar**: `budget-summary-bar*` (web views.cljs:475-730)
- Shows: Brukt, Overforbruk, Ukategorisert, Gjenstår
- Currently has no pace tick

### 1.5 Existing Tests

- `test/common/category_service_test.clj` — Filter matching, synthesize-filter
- `test/odin/services/transaction_service_test.clj` — Integration tests with DynamoDB Local
- `test/odin/services/merge_service_test.clj` — Transaction merge logic

---

## 2. Recurring Payment Detection Algorithm

### 2.1 Description Normalisation

Normalise transaction descriptions to improve grouping:

```clojure
(defn normalise-description [desc]
  (-> desc
      str/lower-case
      (str/replace #"\d{2}[./]\d{2}[./]?\d{0,4}" "")  ; dates: 12/05, 2024-01-15
      (str/replace #"\d{4,}" "")                       ; long numbers (refs, card digits)
      (str/replace #"\*\d+" "")                        ; Vipps refs: *12345
      (str/replace #"\s+" " ")
      str/trim))
```

### 2.2 Grouping and Cadence Detection

**Group transactions** by (category-id, normalised-description, amount-bucket):
- Amount bucket: ±10% tolerance (configurable, default 0.10)

**Detect cadence**:
1. **Monthly**: Present in ≥3 of last 4 months OR ≥9 of last 12 months
2. **Quarterly**: Same month in ≥3 of last 4 quarters
3. **Yearly**: Same month, present in ≥2 of last 3 years

```clojure
(defn recurring-cadence [transaction-dates]
  (let [by-month (group-by #(month-key %) transaction-dates)
        monthly-hits (count (filter #(>= (count (val %)) 1) by-month))]
    (cond
      (>= monthly-hits 9) :monthly
      (quarterly-pattern? transaction-dates) :quarterly
      (yearly-pattern? transaction-dates) :yearly
      :else nil)))
```

### 2.3 Expected Day and Weekend/Holiday Adjustment

**Median day**: Use median day-of-month from historical occurrences.

**Weekend/holiday shift**:
- Norwegian bank holidays: påskemandag, 17. mai, 2. pinsedag, etc.
- If expected day falls on weekend/holiday, shift to next business day
- Store the shift direction seen historically (some payments shift earlier, most shift later)

```clojure
(defn expected-day [historical-days holidays]
  (let [base-day (median historical-days)
        typical-shift (mode (map #(shift-direction % holidays) historical-occurrences))]
    {:base-day base-day
     :shift typical-shift}))  ; :forward, :backward, or nil
```

### 2.4 Reuse of Loan Matching

For categories that already have loan associations:
1. Check if category has matching loan via `matching-txns-for-loan`
2. Use loan's `:payment-history` for refined prediction
3. Apply loan's known monthly payment amount as prediction

---

## 3. Variable Spending Algorithm

### 3.1 Cumulative Shape from History

For non-recurring spending within a category:

1. **Extract daily cumulative** for each of last 12 months
2. **Normalise to 0–1** (divide by month total)
3. **Average the shapes** across months to get typical cumulative curve

```clojure
(defn cumulative-shape [category-id transactions-by-month]
  (let [shapes (for [[month txns] transactions-by-month]
                 (let [daily (group-by day-of-month txns)
                       running (reductions + 0 (map sum-amounts (vals daily)))
                       total (last running)]
                   (when (pos? total)
                     (mapv #(/ % total) running))))]
    (average-shapes (remove nil? shapes))))
```

### 3.2 Robust Month Total Estimate

Estimate expected total spending using recency-weighted, outlier-robust method:

1. Collect last 12 monthly totals for category
2. Apply trimmed mean (remove top/bottom 10%) or median
3. Weight recent months higher: exponential decay with λ = 0.1

```clojure
(defn estimated-total [monthly-totals]
  (let [weights (map #(Math/exp (* -0.1 %)) (range (count monthly-totals)))
        trimmed (trim-outliers monthly-totals 0.1)
        weighted-sum (reduce + (map * trimmed weights))
        weight-total (reduce + weights)]
    (/ weighted-sum weight-total)))
```

### 3.3 Fallback to Straight Line

When data is sparse (< 3 months) or chaotic (coefficient of variation > 0.8):
- Fall back to linear pace: `budget × (day / days-in-month)`

```clojure
(defn use-straight-line? [monthly-totals]
  (or (< (count monthly-totals) 3)
      (> (coefficient-of-variation monthly-totals) 0.8)))
```

### 3.4 Uncertainty Band

For chaotic categories, store 25th and 75th percentile from historical daily values:

```clojure
(defn uncertainty-band [daily-cumulative-samples day]
  (let [values-at-day (map #(nth % day) daily-cumulative-samples)]
    {:p25 (percentile values-at-day 0.25)
     :p75 (percentile values-at-day 0.75)}))
```

---

## 4. Storage Design

### 4.1 New Table: `PacePrediction`

| Attribute | Type | Description |
|-----------|------|-------------|
| `UserId` | S (PK) | User identifier |
| `MonthKey` | S (SK) | `YYYY-MM` format |
| `AlgorithmVersion` | N | Version for cache invalidation |
| `NewestTransactionDate` | S | ISO date of newest txn used |
| `Categories` | S | EDN-encoded map (see below) |
| `CreatedAt` | S | ISO timestamp |
| `FrozenBaseline` | S | EDN of start-of-month values |

**Categories structure** (EDN):
```clojure
{:category-id-1
 {:recurring [{:description "Netflix"
               :expected-day 15
               :expected-amount 179
               :cadence :monthly
               :paid? false}]
  :variable {:shape [0.0 0.03 0.08 ...]  ; 31 values, normalised
             :estimated-total 8500
             :uncertainty {:p25 [...] :p75 [...]}
             :straight-line? false}}
 :category-id-2 {...}}
```

### 4.2 Size Limits

- Max ~30 categories × ~10 recurring payments × ~50 bytes = ~15 KB
- Variable shape: 31 floats × 8 bytes × 30 categories = ~7.5 KB
- Total item size: ~25 KB (well under DynamoDB 400 KB limit)

### 4.3 Schema Creation

Add to `src/odin/db_schemas2.clj`:

```clojure
(def pace-prediction-schema
  (-> (CreateTableRequest/builder)
      (.tableName "PacePrediction")
      (.keySchema [(ks "UserId" KeyType/HASH) (ks "MonthKey" KeyType/RANGE)])
      (.attributeDefinitions [(ad "UserId" ScalarAttributeType/S) 
                              (ad "MonthKey" ScalarAttributeType/S)])
      (.provisionedThroughput (pt 5 5))
      (.build)))
```

### 4.4 Frozen Baseline vs Recomputation

- **Frozen baseline**: The prediction computed at month start, used for "expected by today" tick
- **Recomputation**: After bank sync or rule change, update `:paid?` flags for recurring payments, but keep frozen values for comparison
- Store both: `FrozenBaseline` holds original, `Categories` can be updated

---

## 5. Computation Timing and Performance

### 5.1 When to Compute

1. **First load of month**: If no `PacePrediction` exists for current month
2. **After bank sync**: Update `:paid?` flags, optionally recompute if significant rule changes
3. **After rule change**: Mark prediction as stale, lazy-recompute on next load

### 5.2 Trigger Points

**Backend**:
- `transaction_handler` after successful sync → call `update-pace-predictions`
- `stored-category-response` after filter save → mark prediction stale

**Frontend**:
- Request prediction alongside transactions
- New endpoint: `GET /pace-prediction?month=YYYY-MM`

### 5.3 Lambda Performance Considerations

- **Cold start**: Prediction computation adds ~50–100ms
- **Memory**: 12 months × 500 transactions × 100 bytes = ~600 KB in memory
- **DynamoDB reads**: 1 query for 12 months of transactions (already fetched), 1 write
- **Mitigation**: Compute async after response if slow; cache result

### 5.4 Cost Estimate

- 1 prediction per user per month = 1 WCU × 0.5 KB = negligible
- Read on each load = 1 RCU × 25 KB = 1 RCU
- At 1000 users, 30 loads/month = 30,000 RCUs/month ≈ $0.30/month

---

## 6. API Changes

### 6.1 New Endpoint

```
GET /pace-prediction
Query params: month (YYYY-MM, defaults to current)
Response:
{
  "month": "2026-10",
  "algorithmVersion": 1,
  "newestTransactionDate": "2026-09-28",
  "categories": {...},
  "frozenBaseline": {...}
}
```

### 6.2 Backend Handler

Add to `src/odin/handler.clj`:
```clojure
(cpj/GET "/pace-prediction" params pace/prediction-handler)
```

New service file: `src/odin/services/pace_service.clj`

### 6.3 Frontend Integration

**New subscription** in `src/client/subs.cljs`:
```clojure
(reg-sub :pace-prediction (fn [db _] (:pace-prediction db)))
```

**New event** in `src/client/events.cljs`:
```clojure
(reg-event-fx :request-pace-prediction ...)
```

Request prediction when period is a single month in current year.

---

## 7. Frontend State and UI

### 7.1 State Shape

```clojure
{:pace-prediction
 {:month "2026-10"
  :categories {...}
  :loading? false}}
```

### 7.2 Treemap Tile Pace Tick

In `treemap-rect` (`src/client/components/treemap_component/views.cljs`):

```clojure
(when (and pace-prediction current-month? budgeted?)
  (let [expected (get-in pace-prediction [:categories category-id :expected-today])
        actual (:value item)]
    [:div.pace-tick {:style {:left (str (* 100 (/ expected budget)) "%")}}]))
```

**Visual**:
- Small vertical line or triangle marker
- Position: `expected / budget × tile-width`
- Colour: subtle grey or category colour at reduced opacity

### 7.3 Summary Bar Pace Tick

In `budget-summary-bar*`:
- Add pace tick at aggregate expected position
- Text: "Dag 15 av 30"

### 7.4 Uncertainty Band

For chaotic categories:
- Render as soft gradient zone behind the tick
- Width: p25 to p75
- Colour: semi-transparent category colour

### 7.5 Summary Text

Above summary bar when viewing current month:
```
"Dag 15 av 30 · 58% av budsjettet brukt"
```

### 7.6 Tile Detail Card

On hover/tap, show:
```
Mat
4 800 av 8 000 kr brukt
Forventet i dag: 4 200 kr
[==========|====----]
```

### 7.7 Mobile Parity

Update `src/client/mobile/views.cljs`:
- `mobile-treemap-rect`: Add pace tick
- `budget-summary-bars`: Add pace tick and text

---

## 8. Handling Uncategorised Spending

### 8.1 Options

1. **Exclude from prediction**: Don't show pace for `ukategorisert-out`
2. **Own prediction**: Treat as a meta-category, predict based on historical uncategorised
3. **Defer until categorisation improves**: Show "Ukategorisert spending affects accuracy"

### 8.2 Recommendation

Start with **option 1** (exclude). As categorisation coverage improves (task 1.1–1.2), this becomes less of an issue. Add a tooltip explaining that uncategorised spending is not included in pace.

### 8.3 Future Enhancement

When uncategorised < 10% of total, treat as noise. When > 10%, show warning in summary text.

---

## 9. Testing Strategy

### 9.1 Unit Tests

**New file**: `test/odin/services/pace_service_test.clj`

| Test | Description |
|------|-------------|
| `normalise-description-test` | Date stripping, ref removal |
| `recurring-cadence-test` | Monthly, quarterly, yearly detection |
| `expected-day-test` | Median calculation, weekend shift |
| `cumulative-shape-test` | Shape normalisation, averaging |
| `estimated-total-test` | Trimmed mean, weighting |
| `uncertainty-band-test` | Percentile calculation |

### 9.2 Integration Tests

Extend `test/odin/services/transaction_service_test.clj`:

```clojure
(deftest pace-prediction-creation-test
  (testing "Creates prediction on first load of month"
    ...))

(deftest pace-prediction-update-after-sync-test
  (testing "Updates paid flags after bank sync"
    ...))
```

### 9.3 Frontend Tests

- Manual testing with mock prediction data
- Verify tick positioning at various percentages
- Test uncertainty band rendering

### 9.4 Test Data

Create fixtures with:
- 12 months of realistic transactions
- Mix of recurring (Netflix, insurance) and variable (groceries, restaurants)
- Edge cases: new categories, sparse data, highly variable spending

---

## 10. PR Breakdown

### PR 1: Database Schema and Prediction Storage
- Add `PacePrediction` table schema
- Add `db2` functions for read/write
- **Files**: `db_schemas2.clj`, `db2.clj`
- **Tests**: Table creation, CRUD operations

### PR 2: Description Normalisation and Grouping
- `normalise-description` function
- Transaction grouping by category + normalised description + amount bucket
- **Files**: New `src/odin/services/pace_service.clj`
- **Tests**: Normalisation edge cases

### PR 3: Recurring Payment Detection
- Cadence detection (monthly, quarterly, yearly)
- Expected day calculation with weekend/holiday shift
- **Files**: `pace_service.clj`
- **Tests**: Cadence detection, day prediction

### PR 4: Variable Spending Prediction
- Cumulative shape calculation
- Robust total estimation
- Uncertainty band
- Straight-line fallback
- **Files**: `pace_service.clj`
- **Tests**: Shape averaging, outlier handling

### PR 5: Backend API and Computation Trigger
- `GET /pace-prediction` endpoint
- Trigger computation on first load
- **Files**: `handler.clj`, `pace_service.clj`
- **Tests**: API integration

### PR 6: Frontend State and Subscriptions
- Add `:pace-prediction` to app-db
- Request event and subscription
- **Files**: `events.cljs`, `subs.cljs`, `api.cljs`

### PR 7: Treemap Pace Tick (Web)
- Render tick in `treemap-rect`
- Detail card enhancement
- **Files**: `treemap_component/views.cljs`

### PR 8: Summary Bar Pace Tick (Web)
- Tick in `budget-summary-bar*`
- Summary text update
- **Files**: `treemap_component/views.cljs`, CSS

### PR 9: Mobile Parity
- Pace tick in mobile treemap
- Summary bars update
- **Files**: `mobile/views.cljs`

### PR 10: Uncertainty Band and Polish
- Band rendering for chaotic categories
- Tooltip explaining uncategorised exclusion
- **Files**: Views, CSS

---

## 11. Risks and Open Questions

### 11.1 For Discussion

1. **Weekend/holiday shift direction**: Should we default to "shift forward" (next business day) or detect the pattern from historical data? Detection is more accurate but adds complexity.

2. **Cadence thresholds**: Are ≥3/4 months for monthly and ≥9/12 months too strict or too lenient? Should these be configurable?

3. **Amount tolerance**: 10% tolerance may group distinct recurring payments (e.g., insurance that increases yearly). Should we use absolute tolerance for small amounts and relative for large?

4. **Recomputation frequency**: Should prediction recompute on every bank sync, or only when filters change? Frequent recomputation keeps `:paid?` flags current but costs DynamoDB writes.

5. **Uncategorised handling**: Is showing a warning sufficient, or should we show a "potential pace" based on historical uncategorised patterns?

6. **Payday alignment**: Some users have predictable paydays (e.g., 15th, 25th). Should we detect this and align the variable spending shape around it?

7. **UI density**: Adding pace ticks, uncertainty bands, and detail text may make the treemap visually busy. Should some elements be opt-in via settings?

### 11.2 Technical Risks

| Risk | Mitigation |
|------|------------|
| **Prediction computation too slow** | Compute async after response; cache aggressively |
| **Shape averaging sensitive to outliers** | Use trimmed mean; fall back to straight line |
| **New users have no history** | Default to straight line for first 3 months |
| **Category changes invalidate history** | Re-categorise historical transactions before prediction |
| **Mobile performance** | Keep prediction payload small; compute on backend only |

### 11.3 Dependencies

- Task 3.2 (category-coloured bar with stripes) should be merged first
- Consider running task 1.1 (improve categorisation) in parallel to reduce uncategorised noise

---

## 12. Glossary

| Term | Definition |
|------|------------|
| **Pace tick** | Visual marker showing expected spending position |
| **Cumulative shape** | Normalised curve of spending accumulation within a month |
| **Cadence** | Frequency pattern: monthly, quarterly, or yearly |
| **Frozen baseline** | Start-of-month prediction, preserved for comparison |
| **Uncertainty band** | 25th–75th percentile range for variable categories |
| **Straight-line fallback** | Linear pace when historical data is insufficient |
