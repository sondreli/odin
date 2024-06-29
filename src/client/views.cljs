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
                         color/color-str) (color/generate-hues 16))]
     [:option {:style {:background-color color}} color])])

(defn add-disabled [props expr?]
  (if expr?
    props
    (assoc props :disabled "disabled")))

(defn get-value-of-parent-row [elm]
  (-> elm  .-target (. closest ".row") .-attributes .-value .-value))

(defn category-row [index category]
  (println "edit-category-row: " category)
  [
   [:tr {:value (:id category) :key (:name category) :class "row"} 
    [:td [:a {:on-click #(dispatch [:edit-category3 (get-value-of-parent-row %) index])}
          "Endre"]]
    [:td {:bgcolor (:color category)} (:name category)]
    [:td {:align "right"} (gstring/format "%.2f"
                                          (-> category :amount (* 100) Math/round (/ 100)))]
    [:td [:a {:on-click #(dispatch [:view-category (:name category)])}
          "View"]]
    [:td [:a {:on-click #(dispatch [:edit-category (get-value-of-parent-row %)])}
          "Edit"]]
    [:td [:a {:on-click #(dispatch [:delete-category (get-value-of-parent-row %)])}
          "Del"]]]
   ]
  )

(defn edit-category-row [index category builder-category ready-to-store?]
  (println "edit-category-row edit: " category)
  [[:tr {:value (:id category) :key (:name category) :class "row"}
    [:td [:a {:on-click #(dispatch [:edit-category3 (get-value-of-parent-row %) index])}
          "Lukk"]]
    [:td {:bgcolor (:color category)}
     [:input {:type "text" :placeholder "Navn" :value (:name builder-category)
              :on-change #(dispatch [:update-builder-category-name (-> % .-target .-value)])}]
     (color-selector)]
    [:td {:align "right"} (gstring/format "%.2f"
                                          (-> category :amount (* 100) Math/round (/ 100)))]
    [:td [:a {:on-click #(dispatch [:view-category (:name category)])}
          "View"]]
    [:td [:a {:on-click #(dispatch [:edit-category (get-value-of-parent-row %)])}
          "Edit"]]
    [:td [:a {:on-click #(dispatch [:delete-category (get-value-of-parent-row %)])}
          "Del"]]]
   [:tr {:key (str (:id category) "2")}
    [:td {:style {:vertical-align "top"}}
     [:button (-> {:class "buttom-class"
                   :on-click #(dispatch [:store-category3])}
                  (add-disabled ready-to-store?)) "Lagre"]]
    [:td
     [:textarea {:type "text"
                 :rows 5
                 :value (-> builder-category :marker :value)
                 :on-change #(dispatch [:mark-transactions (-> % .-target .-value)])
                 :style {:width "100%"}}]]]])

(defn categories []
  (let [indexed-categories (map-indexed vector @(subscribe [:summed-categories]))
        builder-category @(subscribe [:builder-category])
        edit-category? (fn [category] (= (:id category) (:id builder-category)))
        _ (println "categories builder-category: " builder-category)
        new-category? (-> builder-category :id (= "new-id"))
        ready-to-store? (category/ready-to-store? builder-category)
        category-rows (map (fn [[index category]]
                             (if (edit-category? category)
                               (edit-category-row index category builder-category ready-to-store?)
                               (category-row index category))) indexed-categories)
        new-category-rows (if new-category?
                            (edit-category-row 0 {:id "new-id"} builder-category ready-to-store?)
                            [[:tr {:key "newrow"}
                              [:td [:a {:on-click #(dispatch [:edit-category3 "new-id" 0])} "Ny"]]]])
        rows (mapcat identity (concat category-rows [new-category-rows]))]
    (.log js/console rows)
    [:div
   [:h3 "Categories"]
   [:table
    [:tbody {:id "categories-tbody"}
     rows]
    ]])
  )

(defn loading-label []
  (let [loading @(subscribe [:loading])]
    [:div
     [:h2 "Loading status:"]
     [:h2 loading]]))

(defn add-color [props transaction category-map]
  (if (and
       (contains? transaction :category-id)
       (contains? (:category transaction) :color))
    (if (contains? (:category transaction) :conflicting-color)
      (assoc props :bgcolor "#f44")
      (assoc props :bgcolor (-> transaction :category :color)))
    props))

(defn add-color2 [props transaction category-map]
  (if (contains? transaction :category-id)
    (assoc props :bgcolor (->> transaction :category-id (get category-map) :color))
    props))

(defn menu-angle []
  [:svg {:class "w-3 h-3 ms-3 ml-1" :aria-hidden "true" :xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 10 6"}
   [:path {:stroke "currentColor" :stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "m1 1 4 4 4-4"}]])

(defn menu-item [label]
  [:a {:href "#"
       :class "rounded bg-gray-200 hover:bg-gray-300 py-0 px-4 block whitespace-no-wrap"}
   label])

(defn submenu [label]
  [:button {:id "doubleDropdownButton"
            :class "flex overflow-hidden items-center justify-between w-full px-4 py-0 hover:bg-gray-100 dark:hover:bg-gray-600 dark:hover:text-white"
            :data-dropdown-toggle "doubleDropdown"
            :data-dropdown-placement "right-start" :type "button"}
   label (menu-angle)])

(defn add-category-menu-edit []
  [:ul.dropdown-content.absolute.hidden.text-gray-700.pt-0
   [:li (menu-item "i kategori")]
   [:li (menu-item "som filter")]])

(defn add-category-menu [categories]
  [:ul.dropdown-content.absolute.hidden.text-gray-700.pt-0.whitespace-nowrap
   [:li.dropdown (submenu "i kategori")
    ; button
    ; div
    [:div {:id "doubleDropdown"
           :class "z-10 hidden bg-white divide-y divide-gray-100 rounded-lg shadow w-44 dark:bg-gray-700"}
     [:ul.dropdown-content.absolute.hidden.text-gray-700.pl-2.ml-24.-mt-6
      {:aria-lablledby "doubleDropdownButton"}
      (for [category categories]
        [:li
         [:a {:href "#"
              :class "rounded bg-gray-200 hover:bg-gray-300 py-0 px-4 block whitespace-no-wrap"}
          (:name category)]])]]
    ]
   [:li.dropdown (submenu "som filter")
    [:ul.dropdown-content.absolute.hidden.text-gray-700.pl-2.ml-24.-mt-6
     (for [category categories]
       [:li
        [:a {:href "#"
             :class "rounded bg-gray-200 hover:bg-gray-300 py-0 px-4 block whitespace-no-wrap"}
         (:name category)]])]]
   ])

(defn transactions-table [transactions categories]
  (let [;transactions @(subscribe [:displayed-transactions])
        builder-category @(subscribe [:builder-category])
        indexed-transactions (map-indexed vector transactions)
        category-map (into {} (map (juxt :id #(identity %)) categories))]
    [:table
     [:tbody {:id "transactions-tbody"}
      (for [[index transaction] indexed-transactions]
       [:tr (-> {:key index}
                (add-color2 transaction category-map))
        [:td {:on-click #(dispatch [:toggle-transaction-row index])} "Insp"]
        [:td {:align "right" :style {:padding-right "1em"}}
         (->> transaction :amount (gstring/format "%.2f"))]
        [:td {:align "right" :style {:padding-right "1em"}}
         (-> transaction :date (date/unixtime->prettydate))]
        [:td (:description transaction)]
        (if (-> transaction :category-id some?)
          [:td {:on-click #(dispatch [:view-transaction-match transaction])} "View"]
          [:td ""])
        (if (-> transaction :category-id nil?)
          [:div.dropdown.inline-block.relative
           [:button ;.bg-gray-300.text-gray-700.font-semibold.py-0.px-4.inline-flex.items-center.text-sm
            {:type "button" :data-dropdown-toggle "dropdown"
             :class "text-white bg-blue-700 hover:bg-blue-800 focus:ring-4 focus:outline-none focus:ring-blue-300 font-medium rounded-lg text-sm px-3 py-2.5 text-center inline-flex items-center dark:bg-blue-600 dark:hover:bg-blue-700 dark:focus:ring-blue-800"}
            ;; [:span "Legg til" (menu-angle)]
            "Legg til " (menu-angle)
            ]
            (if (nil? builder-category)
              (add-category-menu categories)
              (add-category-menu-edit))
            ]
          [:td ""])
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
        displayed-transactions (:displayed-transactions displayed-transactions-data)]
    ;; (println "displayed-transactions-viewer 10 transactions: " (take 10 displayed-transactions))
    (case display-option
      :table (transactions-table displayed-transactions categories)
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
   (filter-path)
  ;;  (transactions-table)
   (diplayed-transactions-toggle-view)
   [:div {:id "mychart"}]
   (displayed-transactions-viewer)])
