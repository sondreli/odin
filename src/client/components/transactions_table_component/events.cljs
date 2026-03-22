(ns client.components.transactions-table-component.events
  (:require [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]
            [client.events.utils :as utils]
            [common.category-service :as category]
            [clojure.string :as s]))

(defn- rebuild-marker [lines]
  (let [value (s/join "\n" lines)]
    {:description (vec lines) :value value}))

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
         filter-stats (category/calculate-filter-statistics all-transactions sub-filter effective-category-id)
         builder-cat (:builder-category db)
         edit-idx (-> db :transaction-row-editor :editing-filter-index)
         updated-db (-> db
                        (assoc-in [:transaction-row-editor :new-sub-filter] sub-filter)
                        (assoc-in [:transaction-row-editor :is-match?] is-match?)
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