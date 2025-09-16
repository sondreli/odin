(ns client.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [clojure.string :as s]
            [client.services.date-service :as date]
            [client.services.color-service :as color]
            [client.routes :as routes]
            [common.category-service :as category]
            [goog.string :as gstring]
            [goog.string.format]
            [goog.object :as g]
            ["d3" :as d3]
            [client.components.chart-component.views :as chart]
            [client.components.period-selector-component.views :as period-sel]
            [client.components.transactions-table-component.views :as t-table]))

;; (defn period-selector []
;;     [:div
;;      [:label "Start date:"]
;;      [:input {:type "date" :value (date/first-day-of-this-month)
;;               :on-change #(dispatch [:set-period-transactions (-> % .-target .-value)])}]
;;      [:input {:type "date" :value (date/last-day-of-this-month)
;;               :on-change #(dispatch [:set-period-transactions (-> % .-target .-value)])}]]
  ;; )

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
  (let [colors (map #(-> [% 0.6 0.9]
                         color/hsv2rgb
                         color/color-base10->base16
                         color/color-str) (color/generate-hues 16))]
    [:div {:ref (fn [el]
                  (when el
                    (let [select-el (.querySelector el "select")
                          choices (js/Choices. select-el
                                               (clj->js {:searchEnabled false
                                                         :itemSelectText ""
                                                         :shouldSort false
                                                         :allowHTML false}))]
                      (set! (.-choicesInstance select-el) choices))))}
     [:select {:id "color-selector"
               :class "color-select"
               :on-change #(-> % .-target .-value set-select-bg)}
      (for [[idx color] (map-indexed vector colors)]
        [:option {:value color
                  :data-color color} color])]]))

(defn add-disabled [props expr?]
  (if expr?
    props
    (assoc props :disabled "disabled")))

(defn get-value-of-parent-row [elm]
  (-> elm  .-target (. closest ".row") .-attributes .-value .-value))

(defn category-row [index category]
  ;; (println "edit-category-row: " category)
  [[:tr {:value (:id category) :key (:name category) :class "row"}
    [:td [:a {:on-click #(dispatch [:edit-category3 (get-value-of-parent-row %) index])}
          "Endre"]]
    [:td {:bgcolor (:color category)} (:name category)]
    [:td {:align "right"} (gstring/format "%.2f"
                                          (-> category :amount (* 100) Math/round (/ 100)))]
    [:td [:a {:on-click #(dispatch [:view-category (:name category)])}
          "View"]]
    [:td [:a {:on-click #(dispatch [:delete-category (get-value-of-parent-row %)])}
          "Del"]]]])

(defn edit-category-row [index category builder-category ready-to-store?]
  ;; (println "edit-category-row edit: " category)
  ;; (println "builder-category: " (-> builder-category :marker))
  ;; (println "builder-category: " (-> builder-category :marker (g/get "value")))
  ;; (println "builder-category: " (-> builder-category type))
  [[:tr {:value (:id category) :key "edit-category-row" :class "row"}
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
       rows]]]))

(defn loading-label []
  (let [loading @(subscribe [:loading])]
    [:div
     [:h2 "Loading status:"]
     [:h2 loading]]))

(defn menu-angle []
  [:svg {:class "w-3 h-3 ms-3 ml-1" :aria-hidden "true" :xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 10 6"}
   [:path {:stroke "currentColor" :stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "m1 1 4 4 4-4"}]])

(defn menu-item [label add-type transaction-desc]
  [:a {:href "#"
       :class "rounded bg-gray-200 hover:bg-gray-300 py-0 px-4 block whitespace-no-wrap"
       :on-click #(dispatch [:add-filter transaction-desc add-type])}
   label])

(defn submenu [label]
  [:button {:id "doubleDropdownButton"
            :class "flex items-center justify-between w-full px-2 py-0 hover:bg-gray-100 dark:hover:bg-gray-600 dark:hover:text-white"
            ;; :class "flex items-center"
            :style {:overflow "hidden"
                    :white-space "nowrap"}
            :data-dropdown-toggle "doubleDropdown"
            :data-dropdown-placement "right-start" :type "button"}
   label (menu-angle)])

(defn add-category-menu-edit [transaction-desc]
  [:ul.dropdown-content.absolute.hidden.text-gray-700.pt-0
   [:li (menu-item "i kategori" :to-category-in-edit transaction-desc)]
   [:li (menu-item "som filter" :as-filter-in-edit transaction-desc)]])

(defn add-category-menu [categories transaction-desc]
  [:ul.dropdown-content.absolute.hidden.text-gray-700.pt-0.whitespace-nowrap
   [:li.dropdown (submenu "i kategori")
    [:ul.dropdown-content.absolute.hidden.text-gray-700.pl-2.ml-24.-mt-6
     {:aria-lablledby "doubleDropdownButton"}
     (for [category categories]
       [:li (menu-item (:name category) :to-category transaction-desc)])]]
   [:li.dropdown (submenu "som filter")
    [:ul.dropdown-content.absolute.hidden.text-gray-700.pl-2.ml-24.-mt-6
     (for [category categories]
       [:li (menu-item (:name category) :as-filter transaction-desc)])]]])

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
        displayed-transactions (:displayed-transactions displayed-transactions-data)
        chart-size (:chart-size displayed-transactions-data)]
    ;; (println "displayed-transactions-viewer 2 transactions: " (take 2 displayed-transactions))
    (case display-option
      :table (t-table/transactions-table displayed-transactions-data categories)
      :bar-chart (chart/stacked-barchart displayed-transactions categories period chart-size))))

(defn test-color [hue]
  (let [hsv [hue 0.6 0.9]
        color-str (-> hsv color/hsv2rgb color/color-base10->base16 color/color-str)]
    (println color-str)
    [:p {:style {:background-color color-str}} "hello color"]))

(defn test-chart []
  [:div
   [:button {:on-click #(dispatch [:draw-chart])} "make chart"]
   [:div {:id "mychart"}]])

(defn test-route []
  [:button {:on-click #(dispatch [:navigate :about])} "Navigate"])

(defn odin-app []
  [:div
   ;;  (map #(test-color %) (color/generate-hues 15))
   (test-route)
   (loading-label)
   (categories)
   (period-sel/period-selector)
   ;;  (test-chart)
   (request-it-button)
   (search-bar)
   (filter-path)
   ;;  (transactions-table)
   (diplayed-transactions-toggle-view)
   (displayed-transactions-viewer)])
