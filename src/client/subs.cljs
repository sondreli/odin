(ns client.subs
  (:require [re-frame.core :refer [reg-sub]]
            [common.category-service :as category]))

(reg-sub
 :auth
 (fn [db _]
   (:auth db)))

(reg-sub
 :balance
 (fn [db _]
   (:balance db)))

(reg-sub
 :bank-reauth
 (fn [db _]
   (:bank-reauth db)))

(reg-sub
 :refreshing?
 (fn [db _]
   (:refreshing? db)))

(reg-sub
 :refresh-result
 (fn [db _]
   (:refresh-result db)))

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
 :pace-prediction
 (fn [db _]
   (:pace-prediction db)))

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
   (:show-categorized-transactions? db)))

(reg-sub
 :show-uncategorized-transactions?
 (fn [db _]
   (:show-uncategorized-transactions? db)))

(reg-sub
 :active-menu
 (fn [db _]
   (:active-menu db)))

(reg-sub
 :theme
 (fn [db _]
   (or (:theme db) :light)))

(reg-sub
 :treemap-show-targets?
 (fn [db _]
   (boolean (:treemap-show-targets? db))))

(reg-sub
 :treemap-show-filters?
 (fn [db _]
   (boolean (:treemap-show-filters? db))))

(reg-sub
 :transactions-search
 (fn [db _]
   (or (:transactions-search db) "")))

(reg-sub
 :reports
 (fn [db _]
   (:reports db)))

(reg-sub
 :tags
 (fn [db _]
   (:tags db)))

(reg-sub
 :loans
 (fn [db _]
   (:loans db)))

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

(reg-sub
 :selected-account-id
 (fn [db _]
   (:selected-account-id db)))

(reg-sub
 :nordnet-import
 (fn [db _]
   (:nordnet-import db)))

(reg-sub
 :grocery-items
 (fn [db _]
   (:grocery-items db)))

(reg-sub
 :grocery-sync
 (fn [db _]
   (:grocery-sync db)))

(reg-sub
 :coop-login
 (fn [db _]
   (:coop-login db)))

(reg-sub
 :wealth-data
 (fn [db _]
   (:wealth-data db)))

(reg-sub
 :wealth-loading?
 (fn [db _]
   (:wealth-loading? db)))

(reg-sub
 :wealth-selected-asset
 (fn [db _]
   (:wealth-selected-asset db :total)))

(reg-sub
 :prices-refreshing?
 (fn [db _]
   (:prices-refreshing? db)))

(reg-sub
 :wealth-method
 (fn [db _]
   (:wealth-method db :fifo)))

(reg-sub
 :leverage-settings
 (fn [db _]
   (:leverage-settings db)))

(reg-sub
 :prices-updated-at
 (fn [db _]
   (get-in db [:wealth-data :prices-updated-at])))

(reg-sub
 :multi-select
 (fn [db _]
   (:multi-select db)))

(reg-sub
 :selected-tag-id
 (fn [db _]
   (:selected-tag-id db)))

(reg-sub
 :selected-tag
 (fn [db _]
   (when-let [tid (:selected-tag-id db)]
     (some #(when (= (:id %) tid) %) (:tags db)))))
