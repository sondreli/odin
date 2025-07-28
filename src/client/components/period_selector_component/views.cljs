(ns client.components.period-selector-component.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [client.services.date-service :as date]
            ))

(defn period-selector []
  (let [period-selector @(subscribe [:period-selector])
        transaction-years @(subscribe [:transaction-years])
        long-view (:long-view period-selector)
        selected-year (date/year-of-period (:selected-period period-selector))
        label-fx (if (-> period-selector :time-unit (= :year))
                   date/localdate-str->year
                   date/localdate-str->month)]
    [:div
     [:div
      ; each unit sets a new period
      ; from the period, the long-view can be derived
      ; long-view could also be described with from, to indices
      [:input (merge {:type "radio" :id "radio-year-length" :name "select-period-length"
                      :on-click #(dispatch [:set-period-type :year])}
                     (if (-> period-selector :period-type (= :year))
                       {:checked true}
                       {}))]
      [:label {:for "radio-year-length"} "Year"]
      [:input (merge {:type "radio" :id "radio-months-length" :name "select-period-length"
                      :on-click #(dispatch [:set-period-type :months])}
                     (if (-> period-selector :period-type (= :months))
                       {:checked true}
                       {}))]
      [:label {:for "radio-month-length"} "Months"]
      [:input (merge {:type "radio" :id "radio-month-length" :name "select-period-length"
                      :on-click #(dispatch [:set-period-type :month])}
                     (if (-> period-selector :period-type (= :month))
                       {:checked true}
                       {}))]
      [:label {:for "radio-month-length"} "Month"]
      ]
     (when (-> period-selector :period-type (= :month))
       [:div
        ; should update the period-selector with new long-view
        ; new period can be derived from current period and new year
        ; new period -> new long view
        ; current period -> select new year -> find new period -> call navigate -> period selector is updated
        ; -> and long view is updated -> which triggers the view -> which will update the select and long-view
        [:select {:on-change #(dispatch [:set-period-year (-> % .-target .-value)])}
         (for [[index year] (map-indexed vector transaction-years)]
           [:option {:key index :selected (when (= year selected-year) "selected")} year])]])
     (if (= (:period-type period-selector) :months)
       (let [selected-range (:selected-range period-selector)]
         [:div
          (for [period long-view]
            (let [is-selected (or (= period (-> period-selector :selected-range first))
                                  (and selected-range
                                       (let [[start end] selected-range
                                             s-idx (.indexOf (vec long-view) start)
                                             e-idx (.indexOf (vec long-view) end)
                                             p-idx (.indexOf (vec long-view) period)]
                                         (and (<= (min s-idx e-idx) p-idx (max s-idx e-idx))
                                              (some? (-> period-selector :selected-range second))))))]
              [:span {:key (:start period)
                      :on-click #(dispatch [:set-selected-range period])
                      :style {:background-color (if is-selected "#88f" "#fff")
                              :cursor "pointer"
                              :margin "0 2px"
                              :padding "2px 4px"
                              ;:border (when is-selected "1px solid #333")
                              }}
               (str (label-fx (:start period)) " ")]))])
       [:div
        (for [period long-view]
          [:span {:key (:start period)
                  :on-click #(dispatch [:navigate [period nil nil]])
                  :style {:background-color (if (= period (:selected-period period-selector)) "#88f" "#fff")
                          :cursor "pointer"
                          :margin "0 2px"
                          :padding "2px 4px"}}
           (str (label-fx (:start period)) " ")])])
     ]))