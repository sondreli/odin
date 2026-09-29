(ns client.components.summed-table-component.views
  (:require [reagent.core :as r]
            [common.category-service :as category]
            [goog.string :as gstring]
            [client.services.date-service :as date]))

(defn group-by-filter-line [transactions category-map]
  (reduce
   (fn [acc transaction]
     (let [category-id (:category-id transaction)
           category (when category-id (get category-map category-id))
           pattern (if category
                     (or (category/find-sub-filter category transaction)
                         (str (:name category) " (no filter match)"))
                     "uncategorized")]
       (update acc pattern (fnil conj []) transaction)))
   {}
   transactions))

(defn- summary-row [pattern transactions expanded? on-toggle category-map max-abs-total]
  (let [count-txns (count transactions)
        total (reduce + 0 (map :amount transactions))
        avg (if (pos? count-txns) (/ total count-txns) 0)
        category (when-let [cid (:category-id (first transactions))]
                   (get category-map cid))
        color (or (and category (:color category)) "#9ca3af")
        share-pct (when (and max-abs-total (pos? max-abs-total))
                    (* 100 (/ (Math/abs total) max-abs-total)))]
    [:tr {:key (str "summary-" pattern)
          :class "txn-row"
          :style {:cursor "pointer"}
          :on-click on-toggle}
     [:td.txn-cat-cell
      [:span.cat-swatch.lg {:style {:background-color color}}]]
     [:td.txn-desc
      [:span {:style {:margin-right "6px" :color "var(--text-faint)"}}
       (if expanded? "▼" "▶")]
      pattern]
     [:td.txn-amount count-txns]
     [:td.txn-amount (gstring/format "%.0f" avg)]
     [:td {:class (str "txn-amount" (when (pos? total) " pos"))}
      (gstring/format "%.0f" total)]
     [:td {:style {:width "120px" :padding "4px 14px"}}
      (when share-pct
        [:div.andel-bar
         [:div {:style {:width (str share-pct "%")
                        :background-color color}}]])]]))

(defn- detail-rows [pattern transactions category-map]
  (for [[idx txn] (map-indexed vector transactions)]
    (let [category (when-let [cid (:category-id txn)]
                     (get category-map cid))
          color (or (and category (:color category)) "#9ca3af")]
      [:tr {:key (str "detail-" pattern "-" idx)
            :style {:font-size "12px"}}
       [:td.txn-cat-cell
        [:span.cat-swatch {:style {:background-color color :opacity 0.5}}]]
       [:td.txn-desc {:style {:padding-left "32px" :color "var(--text-dim)"}}
        (:description txn)]
       [:td.txn-date (date/unixtime->prettydate (:date txn))]
       [:td.txn-amount ""]
       [:td {:class (str "txn-amount" (when (pos? (:amount txn)) " pos"))}
        (gstring/format "%.2f" (:amount txn))]
       [:td]])))

(defn summed-table [_ _]
  (let [expanded (r/atom #{})]
    (fn [displayed-transactions-data categories]
      (let [transactions (:displayed-transactions displayed-transactions-data)
            category-map (into {} (map (juxt :id identity) categories))
            groups (group-by-filter-line transactions category-map)
            sorted-groups (sort-by (fn [[_ txns]] (reduce + 0 (map :amount txns))) groups)
            max-abs-total (when (seq sorted-groups)
                            (apply max (map (fn [[_ txns]]
                                              (Math/abs (reduce + 0 (map :amount txns))))
                                            sorted-groups)))]
        [:table.txn-table
         [:thead
          [:tr
           [:th {:style {:width "32px"}}]
           [:th "Mønster"]
           [:th.txn-amount "Antall"]
           [:th.txn-amount "Snitt"]
           [:th.txn-amount "Sum"]
           [:th "Andel"]]]
         [:tbody
          (doall
           (mapcat
            (fn [[pattern txns]]
              (let [is-expanded? (contains? @expanded pattern)]
                (concat
                 [(summary-row pattern txns is-expanded?
                               #(swap! expanded (fn [s] (if (contains? s pattern)
                                                          (disj s pattern)
                                                          (conj s pattern))))
                               category-map max-abs-total)]
                 (when is-expanded?
                   (detail-rows pattern txns category-map)))))
            sorted-groups))]]))))
