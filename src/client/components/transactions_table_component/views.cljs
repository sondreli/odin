(ns client.components.transactions-table-component.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [common.category-service :as category]
            [goog.string :as gstring]
            [client.services.date-service :as date]
            [clojure.string :as s]))

(defn highlight-text [text filter-text]
  "Highlight the filter text in the description text"
  (if (and (some? filter-text) 
           (not= filter-text "")
           (some? text)
           (s/includes? (s/lower-case text) (s/lower-case filter-text)))
    (let [lower-text (s/lower-case text)
          lower-filter (s/lower-case filter-text)
          start-idx (.indexOf lower-text lower-filter)
          end-idx (+ start-idx (count filter-text))
          before-match (subs text 0 start-idx)
          match-text (subs text start-idx end-idx)
          after-match (subs text end-idx)]
      [:span
       before-match
       [:span {:style {:background-color "yellow" ;:font-weight "bold"
                       }} match-text]
       after-match])
    text))

(defn add-disabled [props expr?]
  (if expr?
    props
    (assoc props :disabled "disabled")))

(defn build-category-select [current-category category-map]
  (let [categories-select (->> (assoc  category-map " " {:name " "})
                               (into [])
                               (sort-by #(first %)))
        category-options (map (fn [[category-id cat]] [:option (if (= (:name cat) (:name current-category))
                                                                 {:key category-id :value category-id :selected "selected"}
                                                                 {:key category-id :value category-id}) (:name cat)]) categories-select)]
    [:select {:on-change #(dispatch [:select-new-category (-> % .-target .-value)])} category-options]))

(defn add-color [props transaction category-map]
  (if (and
       (contains? transaction :category-id)
       (contains? (:category transaction) :color))
    (if (contains? (:category transaction) :conflicting-color)
      (assoc props :bgcolor "#f44")
      (assoc props :bgcolor (-> transaction :category :color)))
    props))

(defn add-color2 [props transaction category-map]
  ;; (when (contains? transaction :category-id)
  ;;   (println "add-color2: " transaction)
  ;;   (println "add-color2: " category-map)
  ;;   (println "add-color2: " (->> transaction :category-id (get category-map) :color)))
  (if (contains? transaction :category-id)
    (assoc props :bgcolor (->> transaction :category-id (get category-map) :color))
    props))

(defn filter-statistics-component []
  (let [stats @(subscribe [:filter-statistics])]
    (println "filter-statistics-component: " stats)
    (when stats
      [:tr {:key "filter-stats"}
       [:td {:col-span 5 :style {:padding "8px" :background-color "#f8f9fa" :font-size "12px" :border-top "2px solid #dee2e6"}}
        [:div {:style {:display "flex" :gap "20px" :justify-content "center" :align-items "center"}}
         [:span {:style {:font-weight "bold" :color "#495057"}} "Filter Statistics:"]
         [:span {:style {:color "#28a745"}} (str "New matches: " (:uncategorized stats))]
         [:span {:style {:color "#007bff"}} (str "Matced already categorized: " (:categorized stats))]
         [:span {:style {:color "#6f42c1"}} (str "Matched same category: " (:same-category stats))]]]])))

(defn build-transaction-row-editor [transaction transaction-row-editor category-map]
  (let [category-id (:category-id transaction)
        _ (println "build-transaction-row-editor: " transaction)
        category (if (contains? transaction-row-editor :new-category)
                    (if (-> transaction-row-editor :new-category some?)
                      (get category-map (-> transaction-row-editor :new-category))
                      nil)
                    (if (-> category-map (get category-id) some?)
                      (-> category-map (get category-id))
                      nil))
        not-category? (nil? category)
        checked? (or (and (not (contains? transaction-row-editor :filter-checked?))
                          (:marked-by-filter? transaction))
                     (-> transaction-row-editor :filter-checked? boolean))
        sub-filter (category/find-sub-filter category transaction)
        new-sub-filter (-> transaction-row-editor :new-sub-filter)
        is-match? (-> transaction-row-editor :is-match?)
        filter-value (if (some? new-sub-filter) new-sub-filter sub-filter)
        filter-input-html (let [init {:type "text" :value filter-value
                                      :on-change #(dispatch [:mark-transaction (-> % .-target .-value)])}]
                            (if (or not-category?
                                    (not checked?)) (assoc init :disabled true) init))
        checkbox-html (let [init {:type "checkbox" :on-click #(dispatch [:toggle-is-transaction-category-filtered (-> % .-target .-checked)])}
                            handle-category #(if (some? category) % (assoc % :disabled true))
                            handle-checked #(if checked? (assoc % :checked true) (assoc % :checked false))]
                           (-> init handle-category handle-checked))]
    [[:tr
      [:td [:button (-> {:class "buttom-class"
                         :on-click #(dispatch [:update-transactions-step-one category transaction])}
                        (add-disabled true)) "Lagre"]]
      [:td (build-category-select category category-map)]
      [:td [:input checkbox-html] "filter: "]
      [:td [:input filter-input-html]]
      [:td (if (some? category)
             (if (and (or sub-filter is-match?) checked?)
               "marked by filter"
               "marked manually")
             "")]]
     (filter-statistics-component) ;; always show stats row (it will only render if stats exist)
    ]))

(defn transaction-row [index transaction builder-category transaction-row-editor category-map]
  (let [is-editing? (and (some? transaction-row-editor)
                         (-> transaction-row-editor :row-index (= index)))
        transaction-row-html [:tr  (-> {:key index} (add-color2 transaction category-map))
                              [:td [:a {:on-click #(dispatch [:edit-transaction-row index])} (if is-editing? "Lukk" "Endre")]
          ;{:on-click #(dispatch [:toggle-transaction-row index])} "Insp"
                               ]
                              [:td {:align "right" :style {:padding-right "1em"}}
                               (->> transaction :amount (gstring/format "%.2f"))]
                              [:td {:align "right" :style {:padding-right "1em"}}
                               (-> transaction :date (date/unixtime->prettydate))]
                              [:td (if (and is-editing?)
                                     (let [new-sub-filter (-> transaction-row-editor :new-sub-filter)
                                           category-id (:category-id transaction)
                                           category (get category-map category-id)
                                           existing-sub-filter (when category (category/find-sub-filter category transaction))
                                           filter-to-highlight (if (and (some? new-sub-filter) (not= new-sub-filter ""))
                                                                 new-sub-filter
                                                                 existing-sub-filter)
                                           filter-value (if (some? new-sub-filter) new-sub-filter existing-sub-filter)
                                           is-filter-empty? (or (nil? filter-value) (= filter-value ""))]
                                       [:div {:style {:display "flex" :align-items "center" :gap "5px"}}
                                        (when is-filter-empty?
                                          [:button {:style {:padding "2px 6px" :font-size "10px" :cursor "pointer"}
                                                    :on-click #(let [selection (.getSelection js/window)
                                                                     selected-text (when (not= (.toString selection) "")
                                                                                     (.toString selection))]
                                                                 (if (and selected-text (not= selected-text ""))
                                                                   (dispatch [:mark-transaction selected-text])
                                                                   (dispatch [:mark-transaction (:description transaction)])))}
                                           "Copy"])
                                        (if (and (some? filter-to-highlight) (not= filter-to-highlight ""))
                                          (highlight-text (:description transaction) filter-to-highlight)
                                          (:description transaction))])
                                     (:description transaction))]
                              (if (-> transaction :category-id some?)
                                [:td {:on-click #(dispatch [:view-transaction-match transaction])} "View"]
                                [:td ""])]]
    [

     (if is-editing?
       (concat [transaction-row-html] (build-transaction-row-editor transaction transaction-row-editor category-map))
       transaction-row-html)
     ]))

(defn add-sort-sigil [column sort-column sort-order]
  (cond
    (and (= column sort-column)
         (= sort-order :reverse)) "▼"
    (= column sort-column) "▲"
    :else [:span {:style {:font-size "0.7em"}} "■"]))

(defn transactions-table [displayed-transactions-data categories]
  (println "drawing transaction-table: " ;(->> transactions (take 20) (map :description))
  )
  (let [builder-category @(subscribe [:builder-category])
        transaction-row-editor @(subscribe [:transaction-row-editor])
        transactions (:displayed-transactions displayed-transactions-data)
        sort-column (-> displayed-transactions-data :sort-column)
        sort-order (-> displayed-transactions-data :sort-order)
        indexed-transactions (map-indexed vector transactions)
        _ (println "transaction-table " (count indexed-transactions))
        category-map (into {} (map (juxt :id #(identity %)) categories))
        rows (mapcat (fn [[index transaction]] (transaction-row index transaction builder-category transaction-row-editor category-map)) indexed-transactions)
        ]
    [:table
     [:tr
      [:th ""]
      [:th {:on-click #(dispatch [:sort-column :amount])} (add-sort-sigil :amount sort-column sort-order)]
      [:th {:on-click #(dispatch [:sort-column :date])} (add-sort-sigil :date sort-column sort-order)]
      [:th {:on-click #(dispatch [:sort-column :description])} (add-sort-sigil :description sort-column sort-order)]
      [:th ""]]
     [:tbody {:id "transactions-tbody"}
      rows]]))
