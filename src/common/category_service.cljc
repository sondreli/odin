(ns common.category-service
  (:require [clojure.string :as s]))

;; (defn mark-if-match [transaction builder-category]
;;   (let [lines (-> builder-category :marker :description)
;;         match (fn [t] (and (contains? transaction :description)
;;                            (s/includes? (-> transaction :description s/lower-case) t)))
;;         match-exists? (some match lines)
;;         color (if (-> builder-category :color some?) (:color builder-category) nil)]
;;     (if match-exists?
;;       (assoc transaction :color color)
;;       transaction)))

;; Compiling a regex is the expensive part of match-fun and the same filter is
;; applied across thousands of transactions, so cache the compiled pattern.
(def ^:private compile-regex
  (memoize (fn [body]
             (try (re-pattern (str ".*" (s/lower-case body) ".*"))
                  (catch #?(:clj Exception :cljs :default) _ nil)))))

(defn match-fun [description subtext]
  (if (-> subtext count (> 0))
    (if (and (s/includes? subtext "regex:")
             (-> subtext (subs 0 6) (= "regex:")))
      (if-let [re (compile-regex (subs subtext 6))]
        (boolean (re-matches re (s/lower-case description)))
        false)
      (s/includes? (s/lower-case description) (s/lower-case subtext)))
    false))

(defn match? [builder-category transaction]
  (let [lines (-> builder-category :marker :description)
        desc (:description transaction)
        match-exists? (and (-> lines count (> 0))
                           (some? desc)
                           (some (partial match-fun desc) lines))]
    match-exists?))

(defn update-if-match [transaction builder-category update-match-fun update-nomatch-fun]
  (let [match-exists? (match? builder-category transaction)
        ]
    (if match-exists?
      (update-match-fun transaction)
      (update-nomatch-fun transaction))))

(defn find-sub-filter [category transaction]
  (let [lines (-> category :marker :description)
        desc (:description transaction)
        matches (filter #(match-fun desc %) lines)]
    (when (seq matches) (last matches))))

(defn find-matching-filters [category transaction]
  (let [filters (:filters category)
        desc (:description transaction)]
    (when (and (seq filters) (some? desc))
      (filterv #(match-fun desc (:text %)) filters))))

(defn collect-tag-ids-from-filters [matching-filters]
  (->> matching-filters
       (mapcat :tag-ids)
       (filter some?)
       distinct
       vec))

(defn apply-tags-from-filters [categories transaction]
  (let [all-matching (mapcat #(find-matching-filters % transaction) categories)
        tag-ids (collect-tag-ids-from-filters all-matching)]
    (if (seq tag-ids)
      (-> transaction
          (update :tag-ids #(vec (distinct (concat (or % []) tag-ids))))
          (assoc :filter-tag-ids tag-ids))
      transaction)))

;; (find-sub-filter {:marker {:description ["test"]}} {:description "my  trans"})

;; color should be a list of two colors
; this is outdated. A transaction have category-id
;; (defn mark-transaction [color tran]
;;   (if (contains? tran :category)
;;     (assoc-in tran [:category :conflicting-color] color)
;;     (assoc tran :category {:color color})))

;; (defn mark-transactions [builder-category transactions]
;;     (let [color (if (-> builder-category :color some?) (:color builder-category) nil)
;;           update-match-fun (partial mark-transaction color)]
;;       (->> transactions
;;        (map #(update-if-match % builder-category update-match-fun identity))
;;        (into [])))
  ;; )

(defn remove-category-if-same [category transaction]
  (if (= (:id category) (:category-id transaction))
    (dissoc transaction :category-id)
    transaction))

(defn update-match [builder-category transaction]
  (assoc transaction :category-id (:id builder-category))
  (-> transaction
      (assoc :category-id (:id builder-category))
      (assoc :marked-by-filter? true)))

(defn have-category-and-marked-by-filter? [builder-category transaction]
  (and (= (:id builder-category)
          (-> transaction :category-id))
       (contains? transaction :marked-by-filter?)))

(defn add-category [transactions builder-category]
  (let [update-match-fun (fn [tran] (assoc tran :category-id (:id builder-category)))
        update-nomatch-fun (partial remove-category-if-same builder-category)]
    (->> transactions
         (map #(update-if-match % builder-category update-match-fun update-nomatch-fun))
         (into []))))

(defn add-transaction [builder-category acc transaction]
  (cond (match? builder-category transaction)
        (let [updated-transaction (update-match builder-category transaction)]
          (-> acc
              (update-in [:updated-seq] conj updated-transaction)
              (update-in [:only-updates] conj (select-keys updated-transaction [:db-id :category-id :marked-by-filter?]))
              (update-in [:only-full-updates] conj updated-transaction)))
        (have-category-and-marked-by-filter? builder-category transaction)
        (-> acc
            (update-in [:updated-seq] conj (-> transaction (dissoc :category-id) (dissoc :marked-by-filter?)))
            (update-in [:only-updates] conj {:db-id (:db-id transaction)
                                             :category-id nil
                                             :old-category-id (:category-id transaction)})
            (update-in [:only-full-updates] conj (-> transaction (dissoc :category-id) (dissoc :marked-by-filter?))))
        :else
        (update-in acc [:updated-seq] conj transaction)))

(defn add-category2 [transactions builder-category]
  (let [fun (partial add-transaction builder-category)]
    (reduce fun {:updated-seq [] :only-full-updates [] :only-updates []} transactions))) ; TODO: remove :only-updates?

(defn delete-category-in-transaction [category-id acc transaction]
  (cond (-> transaction :category-id (= category-id))
        (let [updated-transaction (dissoc transaction :category-id)]
          (-> acc
            (update-in [:all] conj updated-transaction)
            (update-in [:updates] conj updated-transaction)))
        :else
        (update-in acc [:all] conj transaction)))

(defn delete-category [transactions category-id]
  (let [fun (partial delete-category-in-transaction category-id)]
    (reduce fun {:all [] :updates []} transactions)))

(defn categorize-transaction [categories transaction]
  (let [cat-matched (if-some [category (some #(when (match? % transaction) %) categories)]
                      (update-match category transaction)
                      transaction)]
    (apply-tags-from-filters categories cat-matched)))

(defn add-categories [categories transactions]
  (let [categorizer (partial categorize-transaction categories)]
    (map categorizer transactions)))

(defn update-marker [text]
  (let [lines (s/split text #"\n")
        only-filled-lines (filter #(> (count %) 0) lines)]
    {:description only-filled-lines :value text}))

(defn ready-to-store? [builder-category]
  (and
   (-> builder-category :name some?)
   (-> builder-category :name count (> 0))
   (-> builder-category :color some?)
   (-> builder-category :marker some?)
   (-> builder-category :marker :value count (> 0))))

;; (let [transactions [;{:category-id "mat" :description "mega"}
;;                     {:category-id "mat" :description "coop mega"}
;;                     {:category-id "mat" :description "megaflis"}]
;;       builder-category {:id "mat" :marker {:description ["coop mega"]}}]
;;   (add-category2 transactions builder-category))

(defn calculate-filter-statistics [all-transactions filter-text category-id]
  (when (and (some? filter-text) (not= filter-text "") (some? all-transactions))
    ;; Single pass: match once, then partition (was three full passes).
    (let [matches (filter #(and (some? (:description %))
                                (match-fun (:description %) filter-text))
                          all-transactions)
          uncategorized-matches (filterv #(nil? (:category-id %)) matches)
          categorized-matches (filterv #(some? (:category-id %)) matches)
          same-category-matches (filterv #(= (:category-id %) category-id) categorized-matches)]
      {:uncategorized (count uncategorized-matches)
       :uncategorized-transactions uncategorized-matches
       :categorized (count categorized-matches)
       :categorized-transactions categorized-matches
       :same-category (count same-category-matches)
       :same-category-transactions same-category-matches
       :filter-text filter-text})))

;; --- Filter synthesis (programming-by-example) -------------------------------
;; Given positive example descriptions (must match) and negative example
;; descriptions (must not match), suggest a filter pattern. Output is consumed
;; by `match-fun`, so a plain string is a substring filter and a "regex:"-prefixed
;; string is a regex (case-insensitive, wrapped in .*…* against a lowercased desc).

(def ^:private min-fragment-length 2)
(def ^:private max-fragment-words 3)

(defn longest-common-substring
  "Longest contiguous substring (lowercased) shared by every string in `strings`.
  Returns nil when there is no shared substring."
  [strings]
  (let [strings (->> strings (map #(s/lower-case (or % ""))) (remove #(= "" %)))]
    (when (seq strings)
      (let [shortest (apply min-key count strings)
            n (count shortest)]
        (first
         (for [len (range n 0 -1)
               start (range 0 (inc (- n len)))
               :let [candidate (subs shortest start (+ start len))]
               :when (every? #(s/includes? % candidate) strings)]
           candidate))))))

(defn- words [s]
  (->> (s/split (s/lower-case (or s "")) #"\s+")
       (remove #(= "" %))
       vec))

(defn- candidate-fragments
  "Word-aligned n-gram fragments (1..max-fragment-words words, single-space joined)
  that are actually substrings of the lowercased description. These are the building
  blocks the synthesizer matches on — whole words and short phrases, never partial
  words, so suggestions stay readable and respect word boundaries."
  [s]
  (let [lower (s/lower-case (or s ""))
        w (words s)
        n (count w)]
    (->> (for [i (range n)
               len (range 1 (inc (min max-fragment-words (- n i))))]
           (s/join " " (subvec w i (+ i len))))
         (filter #(>= (count %) min-fragment-length))
         (filter #(s/includes? lower %))
         distinct)))

(defn- regex-escape [s]
  (s/replace s #"[.*+?^${}()|\[\]\\]" (fn [m] (str "\\" m))))

(defn- has-letter? [s]
  (boolean (re-find #"[a-zæøå]" s)))

(defn- fragment-rank
  "Lower is better: prefer fragments that contain letters (more meaningful than a
  bare number) and, among those, shorter ones (more general)."
  [frag]
  [(if (has-letter? frag) 0 1) (count frag)])

(defn- greedy-cover
  "Pick a small set of `candidates` (substrings) that together cover every positive
  (each positive contains at least one chosen candidate). Returns nil if the
  candidates cannot cover all positives."
  [positives candidates]
  (let [covers? (fn [frag p] (s/includes? p frag))]
    (loop [uncovered (set positives)
           chosen []]
      (if (empty? uncovered)
        chosen
        (let [best (when (seq candidates)
                     (first (sort-by
                             (fn [f] [(- (count (filter #(covers? f %) uncovered))) ; most coverage first
                                      (if (has-letter? f) 0 1)
                                      (count f)])
                             candidates)))
              newly (when best (filter #(covers? best %) uncovered))]
          (if (seq newly)
            (recur (apply disj uncovered newly) (conj chosen best))
            nil))))))

(defn- common-anchor-words
  "Words (taken from any positive) that occur — as a substring — in every positive.
  Substring rather than whole-word membership, so a shared stem like \"power\" still
  counts even when it lives inside \"vipps*power.no\"."
  [pos]
  (->> pos
       (mapcat words)
       distinct
       (filter (fn [w] (and (>= (count w) min-fragment-length)
                            (every? #(s/includes? % w) pos))))
       vec))

(defn- rank-anchors
  "Order anchor words by usefulness: meaningful (letter-containing) words first,
  then words that already separate on their own (not present in any negative),
  then longer (more specific) words."
  [anchors neg]
  (let [contaminated? (fn [w] (some #(s/includes? % w) neg))]
    (sort-by (fn [w] [(if (has-letter? w) 0 1)
                      (if (contaminated? w) 1 0)
                      (- (count w))])
             anchors)))

(defn- neighbors-of
  "Neighbour words sitting immediately on `side` (:left/:right) of each occurrence
  of token `w` in token-vector `tv`."
  [tv w side]
  (->> (range (count tv))
       (filter #(= (nth tv %) w))
       (keep (fn [i]
               (let [j (if (= side :right) (inc i) (dec i))]
                 (when (and (>= j 0) (< j (count tv)))
                   (nth tv j)))))
       distinct
       vec))

(defn- side-neighbors
  "Distinct neighbour words sitting immediately on `side` (:left/:right) of anchor
  `w` across all positives. Returns nil unless every positive has such a neighbour,
  so the resulting pattern can be anchored on `w` for all examples."
  [w pos-token-vecs side]
  (let [per (map #(neighbors-of % w side) pos-token-vecs)]
    (when (every? seq per)
      (distinct (apply concat per)))))

(defn- anchor-context-pattern
  "Build a pattern that keeps `w` fixed and distinguishes on its `side` neighbours.
  A single neighbour yields a plain literal (\"w nb\"); several yield an anchored
  regex alternation (\"regex:w (a|b)\"). Returns {:pattern :regex?} or nil."
  [w neighbors side]
  (when (seq neighbors)
    (if (= 1 (count neighbors))
      (let [nb (first neighbors)
            plain (if (= side :right) (str w " " nb) (str nb " " w))]
        {:pattern plain :regex? false})
      (let [alt (str "(" (s/join "|" (map regex-escape neighbors)) ")")
            body (if (= side :right) (str (regex-escape w) " " alt)
                     (str alt " " (regex-escape w)))]
        {:pattern (str "regex:" body) :regex? true}))))

(defn- negative-exclusion-pattern
  "When positives and negatives share anchor word `w`, exclude right-neighbour
  word(s) that only appear on the negative side. Emits
  \"regex:w (?!bad1\\b|bad2\\b)\" — more general than listing positive first letters
  or store names. Trims `w` so a required stem like \"obs \" still finds token
  \"obs\". Returns {:pattern :regex?} or nil."
  [w pos-tvs neg-tvs]
  (let [wt (s/trim w)]
    (when (and (not= wt "")
               (seq neg-tvs))
      (let [pos-nbs (mapv #(neighbors-of % wt :right) pos-tvs)
            neg-nbs (mapv #(neighbors-of % wt :right) neg-tvs)
            pos-set (set (mapcat identity pos-nbs))
            ;; Negatives that contain the anchor word (others already won't match).
            contaminated (keep-indexed (fn [i tv]
                                         (when (some #(= % wt) tv) i))
                                       neg-tvs)
            exclusive (->> contaminated
                           (mapcat #(nth neg-nbs %))
                           (remove pos-set)
                           set)]
        (when (and (seq exclusive)
                   ;; Every positive has a right neighbour (lookahead needs the space+word slot).
                   (every? seq pos-nbs)
                   ;; Every contaminated negative is hit by at least one exclusive drop word.
                   (every? (fn [i] (some exclusive (nth neg-nbs i))) contaminated))
          (let [drops (sort exclusive)
                alt (s/join "|" (map (fn [d] (str (regex-escape d) "\\b")) drops))
                body (str (regex-escape wt) " (?!" alt ")")]
            {:pattern (str "regex:" body) :regex? true}))))))

(defn- occurrence-indices [s sub]
  (loop [from 0 acc []]
    (let [i (s/index-of s sub from)]
      (if (nil? i) acc (recur (inc i) (conj acc i))))))

(defn- boundary-symbols
  "Symbols sitting immediately on `side` (:left/:right) of each occurrence of `sub`
  in `s`. A symbol is the adjacent character, or :edge when the occurrence is at the
  start (left) / end (right) of the string — so start/end participates in the same
  distinction as ordinary characters (e.g. ^ARK and *ARK can be ORed together)."
  [s sub side]
  (let [len (count sub)
        n (count s)]
    (->> (occurrence-indices s sub)
         (map (fn [i]
                (if (= side :right)
                  (let [j (+ i len)] (if (= j n) :edge (subs s j (inc j))))
                  (if (= i 0) :edge (subs s (dec i) i)))))
         set)))

(defn- char-class [chars]
  (let [esc (fn [c] (cond (= c "]") "\\]"
                          (= c "^") "\\^"
                          (= c "-") "\\-"
                          (= c "\\") "\\\\"
                          :else c))]
    (str "[" (apply str (map esc (sort chars))) "]")))

(defn- boundary-pattern
  "Keep `w` fixed and distinguish by what sits on `side` of it, treating start/end of
  string as a boundary. ORs the distinguishing pieces, e.g. \"regex:(^ark|[ *]ark)\"
  — ARK at the start OR after a space/star, vs the \"p\" of pARK. Collapses to a pure
  \"regex:^kjell\" or \"regex:power[ .]\" when only one piece is needed. Returns
  {:pattern :regex?} or nil when no clean separation covers every positive."
  [w pos neg side]
  (let [pos-sets (mapv #(boundary-symbols % w side) pos)
        neg-syms (reduce into #{} (map #(boundary-symbols % w side) neg))
        pos-syms (reduce into #{} pos-sets)
        c (into #{} (remove neg-syms pos-syms))]
    (when (and (seq c)
               (every? (fn [ps] (some c ps)) pos-sets))
      (let [chars (remove keyword? c)
            edge? (contains? c :edge)
            ew (regex-escape w)
            edge-part (when edge? (if (= side :left) (str "^" ew) (str ew "$")))
            char-part (when (seq chars)
                        (if (= side :left) (str (char-class chars) ew)
                            (str ew (char-class chars))))
            parts (remove nil? [edge-part char-part])
            body (if (= 1 (count parts)) (first parts) (str "(" (s/join "|" parts) ")"))]
        {:pattern (str "regex:" body) :regex? true}))))

(defn filter-stem
  "The literal stem of a filter — the typed text for a plain filter, or the first
  alphanumeric run inside a regex body. Used to keep a re-synthesized filter
  anchored on what the user originally typed."
  [s]
  (when (and (some? s) (not= s ""))
    (if (and (>= (count s) 6) (= (subs s 0 6) "regex:"))
      (first (re-seq #"[a-z0-9æøå]+" (s/lower-case (subs s 6))))
      (s/lower-case s))))

(defn synthesize-filter
  "Suggest a filter pattern from example descriptions.
  Returns {:pattern str :regex? bool :clean? bool}, where :clean? means the
  pattern matches every positive and no negative.

  Strategy: center on a word/stem common to all positives and make the distinction
  in its surroundings. For the best anchor, try (1) the anchor alone, (2) negative
  exclusion of dropped right-neighbour words (\"regex:obs (?!bygg\\b)\"), (3) positional
  / character-boundary anchoring (\"regex:^kjell\", \"regex:power[ .]\"), then (4) the
  anchor plus its distinguishing positive neighbour words (\"regex:rema (oslo|bergen)\").

  When `required` is given (the stem of the filter that created the example group,
  e.g. \"kjell\"), the result MUST contain it: only anchors containing it are
  considered, so the suggestion can't drift to an unrelated common token (\"no\")
  that would match far outside the group. Without a clean separation it falls back
  to a word-aligned fragment set-cover, then a best-effort literal."
  ([positives negatives] (synthesize-filter positives negatives nil))
  ([positives negatives required]
   (let [pos (->> positives (map #(s/lower-case (or % ""))) (remove #(= "" %)) distinct vec)
         neg (->> negatives (map #(s/lower-case (or % ""))) (remove #(= "" %)) distinct vec)
         req (when (and (some? required) (not= required "")) (s/lower-case required))
         clean? (fn [pattern]
                  (and (every? #(match-fun % pattern) pos)
                       (not-any? #(match-fun % pattern) neg)))
         contaminated? (fn [frag] (some #(s/includes? % frag) neg))]
     (if (empty? pos)
       {:pattern "" :regex? false :clean? false}
       (let [pos-tvs (mapv words pos)
             neg-tvs (mapv words neg)
             base-anchors (->> (concat (common-anchor-words pos)
                                       (some-> (longest-common-substring pos) s/trim not-empty vector))
                               (filter #(>= (count %) min-fragment-length))
                               distinct)
             anchors (if req
                       (->> (cons req base-anchors)
                            (filter #(s/includes? % req))
                            distinct
                            (#(rank-anchors % neg)))
                       (rank-anchors base-anchors neg))
             try-result (fn [m] (when (and m (clean? (:pattern m)))
                                  (assoc m :clean? true)))
             anchor-result
             (some
              (fn [w]
                (or
                 ;; (1) the anchor alone
                 (try-result (when (clean? w) {:pattern w :regex? false}))
                 ;; (2) exclude negative-only right-neighbour words
                 ;;     (obs (?!bygg\b) — prefer over char-class overfitting)
                 (try-result (negative-exclusion-pattern w pos-tvs neg-tvs))
                 ;; (3) boundary context: start/end + adjacent chars, ORed
                 ;;     (^kjell, power[ .], or (^ark|[ *]ark))
                 (some #(try-result (boundary-pattern w pos neg %)) [:left :right])
                 ;; (4) anchor + distinguishing positive neighbour words
                 (some (fn [side]
                         (when-let [nb (side-neighbors w pos-tvs side)]
                           (try-result (anchor-context-pattern w nb side))))
                       [:right :left])))
              anchors)]
         (or
          anchor-result
          ;; A required stem must survive even when we can't cleanly separate, so
          ;; the suggestion stays within the user's group instead of drifting.
          (when req {:pattern req :regex? false :clean? (clean? req)})
          ;; Fallback: clean word-aligned fragment set-cover.
          (let [clean-frags (->> pos (mapcat candidate-fragments) distinct (remove contaminated?))
                covers-all? (fn [frag] (every? #(s/includes? % frag) pos))
                single (->> clean-frags (filter covers-all?) (sort-by fragment-rank) first)]
            (cond
              single
              {:pattern single :regex? false :clean? true}

              :else
              (let [chosen (greedy-cover pos clean-frags)]
                (cond
                  (and chosen (= 1 (count chosen)))
                  {:pattern (first chosen) :regex? false :clean? true}

                  (seq chosen)
                  (let [p (str "regex:(" (s/join "|" (map regex-escape chosen)) ")")]
                    {:pattern p :regex? true :clean? (clean? p)})

                  :else
                  (let [fallback (or (some-> (longest-common-substring pos) s/trim not-empty)
                                     (first pos))]
                    {:pattern fallback :regex? false :clean? (clean? fallback)})))))))))))

(defn tx-key
  "Stable identity for a transaction on the client. The client does not reliably
  preserve a single id field, so transactions are identified by the same tuple the
  rest of the app uses (date + amount + date-index), plus description for safety."
  [t]
  [(:date t) (:amount t) (or (:date-index t) 0) (:description t)])

(defn validate-examples
  "Map of {tx-key -> bool} indicating whether `pattern` matches each transaction's
  description. A blank pattern matches nothing."
  [transactions pattern]
  (into {}
        (map (fn [t]
               [(tx-key t)
                (boolean (and (some? (:description t))
                              (some? pattern) (not= pattern "")
                              (match-fun (:description t) pattern)))])
             transactions)))

(defn filter-diff
  "How `pattern` would change category assignments for `transactions`, relative to
  the target `category-id`. Returns the transactions that would newly enter the
  category (currently uncategorized), those already in it that stay, and conflicts
  (matched transactions belonging to a different category)."
  [transactions pattern category-id]
  (when (and (some? pattern) (not= pattern "") (some? transactions))
    (let [matches? (fn [t] (and (some? (:description t))
                                (match-fun (:description t) pattern)))
          matched (filter matches? transactions)
          newly (filter #(nil? (:category-id %)) matched)
          stays (filter #(= (:category-id %) category-id) matched)
          conflicts (filter #(and (some? (:category-id %))
                                  (not= (:category-id %) category-id))
                            matched)]
      {:newly-categorized (vec newly)
       :newly-categorized-count (count newly)
       :stays-same (vec stays)
       :stays-same-count (count stays)
       :conflicts (vec conflicts)
       :conflicts-count (count conflicts)})))
