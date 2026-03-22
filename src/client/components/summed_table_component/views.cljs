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

(defn summary-row [pattern transactions expanded? on-toggle category-map]
  (let [count-txns (count transactions)
        total (reduce + 0 (map :amount transactions))
        category (when-let [cid (:category-id (first transactions))]
                   (get category-map cid))
        color (or (and category (:color category)) "#e9ecef")]
    [:tr {:key (str "summary-" pattern)
          :style {:cursor "pointer" :background-color "#f8f9fa"}
          :on-click on-toggle}
     [:td {:style {:width "12px" :min-width "12px" :padding 0 :background-color color}}]
     [:td {:style {:padding "4px 8px"}}
      (if expanded? "▼ " "▶ ") pattern]
     [:td {:style {:padding "4px 8px" :text-align "right"}}
      count-txns]
     [:td {:style {:padding "4px 8px" :text-align "right"}}
      (gstring/format "%.2f" total)]]))

(defn detail-rows [pattern transactions category-map]
  (for [[idx txn] (map-indexed vector transactions)]
    (let [category (when-let [cid (:category-id txn)]
                     (get category-map cid))
          color (or (and category (:color category)) "#e9ecef")]
      [:tr {:key (str "detail-" pattern "-" idx)
            :style {:font-size "0.9em"}}
       [:td {:style {:width "12px" :min-width "12px" :padding 0 :background-color color}}]
       [:td {:style {:padding "2px 8px 2px 24px"}}
      (:description txn)]
     [:td {:style {:padding "2px 8px" :text-align "right"}}
      (date/unixtime->prettydate (:date txn))]
     [:td {:style {:padding "2px 8px" :text-align "right"}}
      (gstring/format "%.2f" (:amount txn))]])))

(defn summed-table [_ _]
  (let [expanded (r/atom #{})]
    (fn [displayed-transactions-data categories]
      (let [transactions (:displayed-transactions displayed-transactions-data)
            category-map (into {} (map (juxt :id identity) categories))
            groups (group-by-filter-line transactions category-map)
            sorted-groups (sort-by (fn [[_ txns]] (reduce + 0 (map :amount txns))) groups)]
        [:table
         [:thead
          [:tr
           [:th {:style {:width "12px" :min-width "12px" :padding 0}}]
           [:th {:style {:text-align "left" :padding "4px 8px"}} "Pattern"]
           [:th {:style {:text-align "right" :padding "4px 8px"}} "Count"]
           [:th {:style {:text-align "right" :padding "4px 8px"}} "Sum"]]]
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
                               category-map)]
                 (when is-expanded?
                   (detail-rows pattern txns category-map)))))
            sorted-groups))]])) ))
