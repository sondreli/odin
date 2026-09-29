(ns client.components.transactions-table-component.events
  (:require [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]
            [client.events.utils :as utils]
            [common.category-service :as category]
            [clojure.string :as s]))

(defn- rebuild-marker [lines]
  (let [value (s/join "\n" lines)]
    {:description (vec lines) :value value}))

(defn meaningful-filter?
  "A filter worth evaluating against the whole transaction set. Plain filters
  shorter than 2 chars match almost everything, so we don't run them (avoids
  loading thousands of rows into the table). Regex filters are trusted as-is."
  [s]
  (and (some? s)
       (not= s "")
       (or (and (>= (count s) 6) (= (subs s 0 6) "regex:"))
           (>= (count s) 2))))

(reg-event-db
 :mark-transaction
 (fn [db [_ raw-sub-filter]]
   (let [sub-filter (utils/sanitize-input raw-sub-filter)
         row-index (-> db :transaction-row-editor :row-index)
         new-category-id (-> db :transaction-row-editor :new-category)
         transaction (get (-> db :displayed-transactions-data :displayed-transactions) row-index)
         effective-category-id (or new-category-id (:category-id transaction))
         description (:description transaction)
         is-match? (category/match-fun description sub-filter)
         all-transactions (:all-transactions db)
         filter-stats (when (meaningful-filter? sub-filter)
                        (category/calculate-filter-statistics all-transactions sub-filter effective-category-id))
         builder-cat (:builder-category db)
         edit-idx (-> db :transaction-row-editor :editing-filter-index)
         updated-db (-> db
                        (assoc-in [:transaction-row-editor :new-sub-filter] sub-filter)
                        (assoc-in [:transaction-row-editor :is-match?] is-match?)
                        (assoc-in [:transaction-row-editor :fb-msg] nil)
                        (assoc-in [:transaction-row-editor :filter-statistics] filter-stats))]
     (if (and builder-cat (seq sub-filter))
       (let [existing-lines (vec (or (-> builder-cat :marker :description) []))
             existing-filters (vec (or (:filters builder-cat) []))]
         (if (some? edit-idx)
           (let [new-lines (assoc existing-lines edit-idx sub-filter)
                 new-filters (if (< edit-idx (count existing-filters))
                               (assoc-in existing-filters [edit-idx :text] sub-filter)
                               existing-filters)]
             (-> updated-db
                 (assoc-in [:builder-category :marker] (rebuild-marker new-lines))
                 (assoc-in [:builder-category :filters] new-filters)))
           (let [new-idx (count existing-lines)
                 new-lines (conj existing-lines sub-filter)
                 new-filters (conj existing-filters {:text sub-filter :order-index new-idx :tag-ids []})]
             (-> updated-db
                 (assoc-in [:builder-category :marker] (rebuild-marker new-lines))
                 (assoc-in [:builder-category :filters] new-filters)
                 (assoc-in [:transaction-row-editor :editing-filter-index] new-idx)))))
       updated-db))))

(reg-event-db
 :update-filter-line
 (fn [db [_ index new-text]]
   (let [text (utils/sanitize-input new-text)
         builder-cat (:builder-category db)
         lines (vec (or (-> builder-cat :marker :description) []))
         new-lines (if (< index (count lines))
                     (assoc lines index text)
                     lines)
         filters (vec (or (:filters builder-cat) []))
         updated-filters (if (< index (count filters))
                           (assoc-in filters [index :text] text)
                           filters)
         transactions (:period-transactions db)
         updated-cat (-> builder-cat
                         (assoc :marker (rebuild-marker new-lines))
                         (assoc :filters updated-filters))
         marked-transactions (category/add-category transactions updated-cat)
         tx-editor (:transaction-row-editor db)]
     (cond-> (-> db
                 (assoc :builder-category updated-cat)
                 (assoc-in [:displayed-transactions-data :displayed-transactions] marked-transactions))
       (and tx-editor (= index (:editing-filter-index tx-editor)))
       (assoc-in [:transaction-row-editor :new-sub-filter] text)))))

(reg-event-db
 :add-filter-line
 (fn [db _]
   (let [builder-cat (:builder-category db)
         lines (vec (or (-> builder-cat :marker :description) []))
         new-idx (count lines)
         new-lines (conj lines "")
         filters (vec (or (:filters builder-cat) []))
         new-filters (conj filters {:text "" :order-index new-idx :tag-ids []})]
     (-> db
         (assoc-in [:builder-category :marker] (rebuild-marker new-lines))
         (assoc-in [:builder-category :filters] new-filters)
         (assoc-in [:transaction-row-editor :editing-filter-index] new-idx)))))

(reg-event-db
 :remove-filter-line
 (fn [db [_ index]]
   (let [builder-cat (:builder-category db)
         lines (vec (or (-> builder-cat :marker :description) []))
         new-lines (into (subvec lines 0 index)
                         (subvec lines (inc index)))
         filters (vec (or (:filters builder-cat) []))
         new-filters (if (< index (count filters))
                       (into (subvec filters 0 index)
                             (subvec filters (inc index)))
                       filters)
         transactions (:period-transactions db)
         updated-cat (-> builder-cat
                         (assoc :marker (rebuild-marker new-lines))
                         (assoc :filters new-filters))
         marked-transactions (category/add-category transactions updated-cat)
         edit-idx (-> db :transaction-row-editor :editing-filter-index)
         new-edit-idx (when edit-idx
                        (cond
                          (< edit-idx index) edit-idx
                          (= edit-idx index) nil
                          :else (dec edit-idx)))]
     (cond-> (-> db
                 (assoc :builder-category updated-cat)
                 (assoc-in [:displayed-transactions-data :displayed-transactions] marked-transactions))
       (some? new-edit-idx)
       (assoc-in [:transaction-row-editor :editing-filter-index] new-edit-idx)
       (nil? new-edit-idx)
       (update :transaction-row-editor dissoc :editing-filter-index)))))

(reg-event-db
 :sort-column
 (fn
   [db [_ column]]
   (println "sort-column: " column)
   (let [new-sort-order (if (= :reverse (-> db :displayed-transactions-data :sort-order))
                          :straight
                          :reverse)
         new-displayed-transactions (if (= new-sort-order :reverse)
                                      (->> db :displayed-transactions-data :displayed-transactions
                                           (sort-by column)
                                           reverse
                                           (into []))
                                      (->> db :displayed-transactions-data :displayed-transactions
                                           (sort-by column)
                                           (into [])))]
     (-> db
         (assoc-in [:displayed-transactions-data :sort-column] column)
         (assoc-in [:displayed-transactions-data :sort-order] new-sort-order)
         (assoc-in [:displayed-transactions-data :displayed-transactions] new-displayed-transactions)))))

(reg-event-db
 :toggle-filter-tag
 (fn [db [_ filter-index tag-id]]
   (let [builder-cat (:builder-category db)
         filters (vec (or (:filters builder-cat) []))
         filter-obj (get filters filter-index {})
         current-tags (set (or (:tag-ids filter-obj) []))
         new-tags (if (contains? current-tags tag-id)
                    (vec (disj current-tags tag-id))
                    (vec (conj current-tags tag-id)))
         updated-filter (assoc filter-obj :tag-ids new-tags)
         updated-filters (assoc filters filter-index updated-filter)]
     (assoc-in db [:builder-category :filters] updated-filters))))

(reg-event-fx
 :save-filter-tags
 (fn [{db :db} [_ _filter-index]]
   (let [builder-cat (:builder-category db)
         filters (vec (or (:filters builder-cat) []))
         filters-to-save (mapv (fn [f]
                                 (merge {:category-id (:id builder-cat)
                                         :text (or (:text f) "")
                                         :order-index (or (:order-index f) 0)
                                         :tag-ids (or (:tag-ids f) [])}
                                        (when (:id f) {:id (:id f)})))
                               filters)]
     {:dispatch [:save-category-filters filters-to-save]})))

; :toggle-transaction-tag is defined in client.events (needs HTTP persistence)

;; --- Inline filter builder (programming-by-example) -------------------------
;; Lives directly in the transaction row editor. The editor's :new-sub-filter is
;; the single source of truth for the pattern; the merged matches table reads the
;; live :filter-statistics. Each row can be marked positive (:fb-positives) or
;; negative (:fb-negatives). Toggling never regenerates the pattern — the UI just
;; highlights "Foreslå filter" — and :fb-suggest synthesizes a new pattern.

(defn- displayed-transactions [db]
  (-> db :displayed-transactions-data :displayed-transactions))

(defn- fb-suggest-pattern
  "Synthesize a pattern from the current example sets.
  - Negatives: the explicitly excluded transactions.
  - Positives: when some rows are explicitly marked, ONLY those (plus the edited
    transaction) — this lets the user pin a few examples when there are too many
    matches. Otherwise the edited transaction plus everything the current pattern
    matches (minus the negatives)."
  [db]
  (let [editor (:transaction-row-editor db)
        positives (or (:fb-positives editor) #{})
        negatives (or (:fb-negatives editor) #{})
        all (:all-transactions db)
        by-key (into {} (map (juxt category/tx-key identity) all))
        cur-pattern (:new-sub-filter editor)
        base-tx (get (displayed-transactions db) (:row-index editor))
        matches (when (and cur-pattern (not= cur-pattern ""))
                  (filter #(and (some? (:description %))
                                (category/match-fun (:description %) cur-pattern))
                          all))
        ;; Positives: explicit Match rows when any are marked; otherwise everything
        ;; the current filter matches; otherwise (no matches yet) seed from the edited
        ;; transaction. The edited transaction is NOT force-added — it may be an
        ;; unrelated row the editor was opened on (e.g. a REMA row while building a
        ;; KJELL filter), which would make the examples contradictory.
        pos-source (cond
                     (seq positives) (keep by-key positives)
                     (seq matches)   matches
                     :else           [base-tx])
        pos-descs (->> pos-source
                       (remove nil?)
                       (remove #(contains? negatives (category/tx-key %)))
                       (map :description)
                       (remove nil?)
                       distinct)
        neg-descs (->> negatives (keep by-key) (map :description) (remove nil?) distinct)
        ;; The filter that created the current group must remain part of the result,
        ;; so the suggestion can't drift to an unrelated common token.
        required (category/filter-stem cur-pattern)]
    (:pattern (category/synthesize-filter pos-descs neg-descs required))))

(reg-event-db
 :fb-set-example
 (fn [db [_ tx-k polarity]]
   ;; Set a row's polarity (:positive / :negative / :neutral). Does NOT regenerate
   ;; the pattern — the UI highlights "Foreslå filter" when it goes stale.
   (let [editor (:transaction-row-editor db)
         pos (disj (or (:fb-positives editor) #{}) tx-k)
         neg (disj (or (:fb-negatives editor) #{}) tx-k)
         [pos neg] (case polarity
                     :positive [(conj pos tx-k) neg]
                     :negative [pos (conj neg tx-k)]
                     [pos neg])]
     (-> db
         (assoc-in [:transaction-row-editor :fb-positives] pos)
         (assoc-in [:transaction-row-editor :fb-negatives] neg)
         (assoc-in [:transaction-row-editor :fb-msg] nil)))))

(reg-event-fx
 :fb-suggest
 (fn [{db :db} _]
   (let [cur (-> db :transaction-row-editor :new-sub-filter)
         suggestion (fb-suggest-pattern db)]
     (if (= suggestion cur)
       ;; Nothing to improve with the current selection — tell the user why instead
       ;; of silently doing nothing.
       {:db (assoc-in db [:transaction-row-editor :fb-msg]
                      "Fant ikke et smalere filter. Marker treffene som ikke skal med som «Utelat».")}
       {:db (assoc-in db [:transaction-row-editor :fb-msg] nil)
        :dispatch [:mark-transaction suggestion]}))))