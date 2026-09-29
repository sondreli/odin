(ns client.components.ui.metrics-bar
  (:require [re-frame.core :refer [subscribe dispatch]]
            [client.components.ui.status-tile :refer [status-tile]]
            [client.services.format-service :as fmt]
            [goog.string :as gstring]
            [goog.string.format]))

(defn- single-month? [period]
  (= :month (:period-type period)))

(defn metrics-bar
  "Top-of-page metrics for Transaksjoner.
   Five status tiles + Vis budsjett / Vis filtre checkboxes."
  []
  (let [balance-data    @(subscribe [:balance])
        period          @(subscribe [:period])
        period-txns     @(subscribe [:period-transactions])
        show-targets?   @(subscribe [:treemap-show-targets?])
        show-filters?   @(subscribe [:treemap-show-filters?])
        saldo (:available-balance balance-data)
        amounts (map :amount period-txns)
        inn   (reduce + 0 (filter pos? amounts))
        ut    (reduce + 0 (map #(Math/abs %) (filter neg? amounts)))
        netto (- inn ut)
        sparerate (when (pos? inn) (* 100 (/ netto inn)))
        sm? (single-month? period)]
    [:div.metrics-bar
     [status-tile {:label "Saldo"
                   :value (if saldo (fmt/format-kr saldo) "—")}]
     [status-tile {:label "Inn"
                   :value  (fmt/format-kr inn)
                   :accent "var(--c-up)"}]
     [status-tile {:label "Ut"
                   :value  (fmt/format-kr ut)
                   :accent "var(--c-down)"}]
     [status-tile {:label "Netto"
                   :value  (fmt/format-signed-kr netto)
                   :accent (if (neg? netto) "var(--c-down)" "var(--c-up)")}]
     [status-tile {:label "Sparerate"
                   :value (if sparerate (gstring/format "%.1f%%" sparerate) "—")}]
     [:div {:style {:margin-left "auto" :display "flex" :align-items "center" :gap "12px"}}
      (when sm?
        [:label.check-inline
         [:input {:type "checkbox"
                  :checked (boolean show-targets?)
                  :on-change #(dispatch [:set-treemap-show-targets! (not show-targets?)])}]
         [:span "Vis budsjett"]])
      [:label.check-inline
       [:input {:type "checkbox"
                :checked (boolean show-filters?)
                :on-change #(dispatch [:set-treemap-show-filters! (not show-filters?)])}]
       [:span "Vis filtre"]]]]))
