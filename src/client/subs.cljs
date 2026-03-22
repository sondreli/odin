(ns client.subs
  (:require [re-frame.core :refer [reg-sub]]
            [common.category-service :as category]))

(reg-sub
 :loading  ;; usage: (subscribe [:loading])
 (fn [db _]
   (:loading db)))

(reg-sub
 :displayed-transactions
 (fn [db _]
   (:displayed-transactions db)))

(reg-sub
 :displayed-transactions-data
 (fn [db _]
   (:displayed-transactions-data db)))

(reg-sub
 :categories
 (fn [db _]
   (:categories db)))

(reg-sub
 :builder-category
 (fn [db _]
   (:builder-category db)))

(reg-sub
 :summed-categories
 (fn [db _]
   (:summed-categories db)))

(reg-sub
 :period
 (fn [db _]
   (:period db)))

(reg-sub
 :period-selector
 (fn [db _]
   (:period-selector db)))

(reg-sub
 :transaction-years
 (fn [db _]
   (-> db :period-selector :transaction-years)))

(reg-sub
 :filter-path
 (fn [db _]
   (:filter-path db)))

(reg-sub
 :transaction-row-editor
 (fn [db _]
   (:transaction-row-editor db)))

(reg-sub
 :all-transactions
 (fn [db _]
   (:all-transactions db)))

(reg-sub
 :filter-statistics
 (fn [db _]
   (:filter-statistics db)))

(reg-sub
 :show-categorized-transactions?
 (fn [db _]
   (let [result (:show-categorized-transactions? db)]
     (println "show-categorized-transactions? subscription called, result:" result)
     result)))

(reg-sub
 :show-uncategorized-transactions?
 (fn [db _]
   (let [result (:show-uncategorized-transactions? db)]
     (println "show-uncategorized-transactions? subscription called, result:" result)
     result)))

(reg-sub
 :active-menu
 (fn [db _]
   (:active-menu db)))

(reg-sub
 :reports
 (fn [db _]
   (:reports db)))

(reg-sub
 :tags
 (fn [db _]
   (:tags db)))

(reg-sub
 :period-transactions
 (fn [db _]
   (:period-transactions db)))

(reg-sub
 :reports-period
 (fn [db _]
   (:reports-period db)))

(reg-sub
 :auth
 (fn [db _]
   (:auth db)))

(reg-sub
 :auth-view
 (fn [db _]
   (:auth-view db)))

(reg-sub
 :accounts
 (fn [db _]
   (:accounts db)))
