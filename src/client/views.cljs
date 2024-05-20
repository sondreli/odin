(ns client.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [clojure.string :as s]
            [client.services.date-service :as date]
            [client.services.color-service :as color]
            [client.routes :as routes]
            [common.category-service :as category]
            [goog.string :as gstring]
            [goog.string.format]
            ["d3" :as d3]
             
            [client.services.chart-service :as chart]))

;; (defn period-selector []
;;     [:div
;;      [:label "Start date:"]
;;      [:input {:type "date" :value (date/first-day-of-this-month)
;;               :on-change #(dispatch [:set-period-transactions (-> % .-target .-value)])}]
;;      [:input {:type "date" :value (date/last-day-of-this-month)
;;               :on-change #(dispatch [:set-period-transactions (-> % .-target .-value)])}]]
  ;; )

(defn period-selector []
  (let [period-selector @(subscribe [:period-selector])
        transaction-years @(subscribe [:transaction-years])
        long-view (:long-view period-selector)
        selected-year (date/year-of-period (period-selector :selected-period))
        label-fx (if (-> period-selector :time-unit (= :year))
                   date/localdate-str->year
                   date/localdate-str->month)]
    [:div
     [:div
      ; each unit sets a new period
      ; from the period, the long-view can be derived
      ; long-view could also be described with from, to indices
      [:input (merge {:type "radio" :id "radio-year-length" :name "select-period-length"
                      :on-click #(dispatch [:set-time-unit :year])}
                     (if (-> period-selector :time-unit (= :year))
                       {:checked true}
                       {}))]
      [:label {:for "radio-year-length"} "Year"]
      [:input (merge {:type "radio" :id "radio-month-length" :name "select-period-length"
                      :on-click #(dispatch [:set-time-unit :month])}
                     (if (-> period-selector :time-unit (= :month))
                       {:checked true}
                       {}))]
      [:label {:for "radio-month-length"} "Month"]
      ]
     (when (-> period-selector :time-unit (= :month))
       [:div
        ; should update the period-selector with new long-view
        ; new period can be derived from current period and new year
        ; new period -> new long view
        ; current period -> select new year -> find new period -> call navigate -> period selector is updated
        ; -> and long view is updated -> which triggers the view -> which will update the select and long-view
        [:select {:on-change #(dispatch [:set-period-year (-> % .-target .-value)])}
         (for [[index year] (map-indexed vector transaction-years)]
           [:option {:key index :selected (when (= year selected-year) "selected")} year])]])
     [:div
      (for [period long-view]
       [:span {:on-click #(dispatch [:navigate [period nil nil]])
               :style {:background-color (if (= period (:selected-period period-selector)) "#88f" "#fff")}}
        (str (label-fx (:start period)) " ")])]
     ]))

(defn filter-path []
  (let [html-path (->> @(subscribe [:filter-path])
                       (concat ["All"])
                       (map-indexed vector)
                       (map (fn [[index level]]
                              [:span {:on-click #(dispatch [:filter-path index])} level])))
        path (interpose " > " html-path)]
    [:div
     (for [elm path]
       elm)]))

(defn startup []
  (dispatch [:request-all-transactions])
  (dispatch [:request-all-categories]))

(defn request-it-button
  []
  [:button {:class "button-class"
            :on-click  #(startup)}
        "I want it, now!"])

(defn search-bar []
  [:input {:type "text"
           :on-change #(dispatch [:filter-transactions (-> % .-target .-value)])}])

(defn set-select-bg [color]
  (let [select (. js/document getElementById "color-selector")
        _ (set! (.. select -style -backgroundColor) color)]
    (dispatch [:update-builder-category-color color])))

(defn color-selector []
  [:select {:id "color-selector" :on-change #(-> % .-target .-value set-select-bg)}
   (for [color (map #(-> [% 0.6 0.9]
                         color/hsv2rgb
                         color/color-base10->base16
                         color/color-str) (color/generate-hues 10))]
     [:option {:style {:background-color color}} color])])

(defn add-disabled [props expr?]
  (if expr?
    props
    (assoc props :disabled "disabled")))

(defn category-builder []
    (let [builder-category @(subscribe [:builder-category])
          ready-to-store? (category/ready-to-store? builder-category)]
      (println "category-builder view: " (-> builder-category :marker type))
      [:div
       [:div
        [:input {:type "text" :placeholder "Navn"
                 :value (-> builder-category :name)
                 :on-change #(dispatch [:update-builder-category-name (-> % .-target .-value)])}]
        ;; [:input {:type "text" :placeholder "Farge"
        ;;          :value (-> builder-category :color-value)
        ;;          :on-change #(dispatch [:update-builder-category-color (-> % .-target .-value)])}]
        (color-selector)
        [:button (-> {:class "buttom-class"
                      :on-click #(dispatch [:store-category2])}
                     (add-disabled ready-to-store?)) "Lagre"]]
       [:textarea {:type "text"
                   :rows 5
                   :value (-> builder-category :marker :value)
                   :on-change #(dispatch [:mark-transactions (-> % .-target .-value)])}]])
  )

(defn get-value-of-parent-row [elm]
  (-> elm  .-target (. closest ".row") .-attributes .-value .-value))

(defn categories []
  [:div
   [:h3 "Categories"]
   [:table
    (for [category @(subscribe [:summed-categories])]
      [:tr {:value (:name category) :key (:name category) :class "row"}
       [:td {:bgcolor (:color category)} (:name category)]
       [:td {:align "right"} (gstring/format "%.2f"
                                             (-> category :amount (* 100) Math/round (/ 100)))]
       [:td [:a {:on-click #(dispatch [:view-category (get-value-of-parent-row %)])}
             "View"]]
       [:td [:a {:on-click #(dispatch [:edit-category (get-value-of-parent-row %)])}
             "Edit"]]
       [:td [:a {:on-click #(dispatch [:delete-category (get-value-of-parent-row %)])}
             "Del"]]])]
   ])

(defn loading-label []
  (let [loading @(subscribe [:loading])]
    [:div
     [:h2 "Loading status:"]
     [:h2 loading]]))

(defn add-color [props transaction]
  (if (and
       (contains? transaction :category)
       (contains? (:category transaction) :color))
    (if (contains? (:category transaction) :conflicting-color)
      (assoc props :bgcolor "#f44")
      (assoc props :bgcolor (-> transaction :category :color)))
    props))

(defn transactions-table [transactions]
  (let [;transactions @(subscribe [:displayed-transactions])
        indexed-transactions (map-indexed vector transactions)]
    [:table
     [:tbody {:id "transactions-tbody"}
      (for [[index transaction] indexed-transactions]
       [:tr (-> {:key index}
                (add-color transaction))
        [:td {:on-click #(dispatch [:toggle-transaction-row index])} "Insp"]
        [:td {:align "right" :style {:padding-right "1em"}}
         (->> transaction :amount (gstring/format "%.2f"))]
        [:td {:align "right" :style {:padding-right "1em"}}
         (-> transaction :date (date/unixtime->prettydate))]
        [:td (:description transaction)]
        (when (-> transaction :category some?)
          [:td {:on-click #(dispatch [:view-transaction-match transaction])} "View"])
        ]
               )]]))

(defn diplayed-transactions-toggle-view []
  [:button {:on-click #(dispatch [:toggle-chart])} "Toggle bar-chart"])

(defn remove-barchart []
  (-> d3 (.selectAll "#mychart svg") (.remove)))

(defn displayed-transactions-viewer []
  (remove-barchart)
  (let [displayed-transactions-data @(subscribe [:displayed-transactions-data])
        categories @(subscribe [:categories])
        period @(subscribe [:period])
        display-option (:display-option displayed-transactions-data)
        _ (println "displayed-transactions-viewer: " display-option)
        _ (println (str "display-option: " display-option))
        displayed-transactions (:displayed-transactions displayed-transactions-data)]
    (case display-option
      :table (transactions-table displayed-transactions)
      :bar-chart (chart/draw-stacked-barchart displayed-transactions categories period))
    ))

(defn test-color [hue]
  (let [hsv [hue 0.6 0.9]
        color-str (-> hsv color/hsv2rgb color/color-base10->base16 color/color-str)]
    (println color-str)
    [:p {:style {:background-color color-str}} "hello color"]))


(defn test-chart []
  [:div
   [:button {:on-click #(dispatch [:draw-chart])} "make chart"]
   [:div {:id "mychart"}]]
  )

(defn test-route []
  [:button {:on-click #(dispatch [:navigate :about])} "Navigate"])



(defn odin-app []
  [:div
  ;;  (map #(test-color %) (color/generate-hues 15))
  (test-route)
   (loading-label)
   (categories)
   (period-selector)
  ;;  (test-chart)
   (request-it-button)
   (search-bar)
   (category-builder)
   (filter-path)
  ;;  (transactions-table)
   (diplayed-transactions-toggle-view)
   [:div {:id "mychart"}]
   (displayed-transactions-viewer)])
