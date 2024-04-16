(ns client.subs
  (:require [re-frame.core :refer [reg-sub]]))

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
 :transaction-years
 (fn [db _]
   (-> db :period-selector :transaction-years)))

(reg-sub
 :filter-path
 (fn [db _]
   (:filter-path db)))