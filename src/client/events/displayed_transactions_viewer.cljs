(ns client.events.displayed-transactions-viewer
  (:require [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]
            [common.category-service :as category]
            [client.events.utils :as utils]))

(defn add-transaction-row [index table-index-offset transaction]
  (let [tbody (. js/document getElementById "transactions-tbody")
        row (. tbody insertRow (+ index table-index-offset 1))
        new-cell (. row insertCell 0)
        _ (. new-cell setAttribute "colspan" "3")
        ;; _ (. row addEventListener "click" #(dispatch [:toggle-transaction-row index]))
        text (. js/document createTextNode (.stringify js/JSON (clj->js transaction)))]
    (. new-cell appendChild text)))

(defn delete-transaction-row [index table-index-offset transaction]
  (let [tbody (. js/document getElementById "transactions-tbody")
        row (. tbody deleteRow (+ index table-index-offset 1))
        ]))

(reg-event-db
 :toggle-transaction-row
 (fn
   [db [_ index]]           ;; destructure the response from the event vector
   (let [maybe-value (:open-transaction-rows db)
         open-rows (if (nil? maybe-value) #{} maybe-value)
         is-open? (contains? open-rows index)
         table-index-offset (count (filter #(< % index) open-rows))
         updated-open-rows (if is-open? (disj open-rows index) (conj open-rows index))
         transaction (-> db :displayed-transactions (get index))]
     (if is-open?
       (delete-transaction-row index table-index-offset transaction)
       (add-transaction-row index table-index-offset transaction))
     (-> db
       ;(assoc :open-transaction-rows)
         (assoc :open-transaction-rows updated-open-rows)))
   ))

(defn find-matcher [transaction category]
  (let [matchers (-> category :marker :description)
        ;(some (partial match-fun desc) lines)
        desc (-> transaction :description)
        matcher (some #(when (category/match-fun desc %) %) matchers)]
    matcher))

;; (reg-event-db
;;  :view-transaction-match
;;  (fn
;;    [db [_ transaction]]
;;    (let [filter-path (if (-> db :filter-path count (= 2))
;;                        [(-> db :filter-path first)]
;;                        (let [category (some #(when (= (-> transaction :category :name) (:name %)) %)
;;                                             (:categories db))
;;                              matcher (find-matcher transaction category)]
;;                          [(:name category) matcher]))]
;;      (-> db
;;          (assoc :filter-path filter-path)
;;          (assoc :displayed-transactions-data (utils/displayed-transactions-data db nil filter-path nil))))))
(reg-event-fx
 :view-transaction-match
 (fn
   [{db :db
     [_ transaction] :event} _]
   (let [filter-path (if (-> db :filter-path count (= 2))
                       [(-> db :filter-path first)]
                       (let [category (some #(when (= (:category-id transaction) (:id %)) %)
                                            (:categories db))
                             matcher (find-matcher transaction category)]
                         [(:name category) matcher]))]
     (dispatch [:navigate [nil nil filter-path]]))))

(reg-event-db
 :view-category-period
 (fn [db [_ [filter-path period display-option]]]
     (println "view-category-period")
   (let []
     (println filter-path period display-option)
     (utils/apply-update db period filter-path display-option))))

(defn as-filter-in-edit [db category]
  (-> db
      (update-in [:builder-category :marker :value] str "\n" category)
      (update-in [:builder-category :marker :description] conj category)))

(reg-event-db
 :add-filter
 (fn [db [_ transaction-desc add-category]]
   (println "add-filter: " transaction-desc)
   (case add-category
     :as-filter-in-edit (as-filter-in-edit db transaction-desc)
     :to-category-in-edit (as-filter-in-edit db transaction-desc)
     :as-filter (as-filter-in-edit db transaction-desc)
     :to-category (as-filter-in-edit db transaction-desc))))