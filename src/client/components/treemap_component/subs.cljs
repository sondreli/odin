(ns client.components.treemap-component.subs
  (:require [re-frame.core :refer [reg-sub]]))

(reg-sub
 :treemap-target
 (fn [db _]
   (:treemap-target db)))
