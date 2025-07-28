(ns client.components.transactions-table-component.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [common.category-service :as category]
            [goog.string :as gstring]
            [client.services.date-service :as date]
            ))

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

(defn build-transaction-row-editor [transaction transaction-row-editor category-map]
  (let [category-id (:category-id transaction)
        ;; _ (println "is-editing? " transaction-row-editor)
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
        ;; _ (println "category-nil?: " (nil? category))
             ; when category changed, old filter will not match, should it then be red or marked out?
             ; maybe I need a state machine? Mark out the different states
        sub-filter (category/find-sub-filter category transaction) ; from existing category
        new-sub-filter (-> transaction-row-editor :new-sub-filter)
        is-match? (-> transaction-row-editor :is-match?)
        filter-value (if (some? new-sub-filter) new-sub-filter sub-filter)
        ;; _ (println "filter: " sub-filter " new-filter: " new-sub-filter "is-match?: " is-match?)
        ;; _ (println "checked?: " checked? "type: " (type checked?))
        ;; _ (println "category: " category)
        ;; _ (println "tran-category: " (-> category-map (get category-id)) " new-category-edit? " (contains? transaction-row-editor :new-category) " new-category: " (-> transaction-row-editor :new-category))
        filter-input-html (let [init {:type "text" :value filter-value
                                      :on-change #(dispatch [:mark-transaction (-> % .-target .-value)])}]
                            (if (or not-category?
                                    (not checked?)) (assoc init :disabled true) init)) 
        checkbox-html (let [init {:type "checkbox" :on-click #(dispatch [:toggle-is-transaction-category-filtered (-> % .-target .-checked)])}
                            handle-category #(if (some? category) % (assoc % :disabled true))
                            handle-checked #(if checked? (assoc % :checked true) (assoc % :checked false))]
                           (-> init handle-category
                               handle-checked
                               ))
        ;; _ (println "input-html: " filter-input-html)
        ]
    [:tr {:key "transaction-row-editor"}
     [:td [:button (-> {:class "buttom-class"
                        ; need to update the category
                        ; get the value in text field
                        ; data is in category-editor
                        ; only allow click when transaction-row-editor contians valid category update
                        ; take new-sub-filter and add it to category
                        :on-click #(dispatch [:update-transactions-step-one category transaction])}
                       (add-disabled true)) "Lagre"]]
     [:td (build-category-select category category-map)]
          ; available if have category
     ;[:td [:input (if (and is-match? (some? category)) {:type "checkbox" :checked "checked"} {:type "checkbox"})] "filter: "] ; when have filter
     [:td [:input checkbox-html] "filter: "] ; when have filter
     [:td [:input filter-input-html]]
     [:td (if (some? category)
            (if (and (or sub-filter ; hit by category marker
                         is-match?); hit by new sub-filter
                     checked?)                          
              ; if have new sub-filter and not match, then false
              "marked by filter" ; have category, checked and filter match
              "marked manually") ; have category and not checked
            "")]]))

(defn transaction-row [index transaction builder-category transaction-row-editor category-map]
  (let [is-editing? (and (some? transaction-row-editor)
                         (-> transaction-row-editor :row-index (= index)))]
    [[:tr  (-> {:key index} (add-color2 transaction category-map))
      [:td [:a {:on-click #(dispatch [:edit-transaction-row index])} (if is-editing? "Lukk" "Endre")]
          ;{:on-click #(dispatch [:toggle-transaction-row index])} "Insp"
       ]
      [:td {:align "right" :style {:padding-right "1em"}}
       (->> transaction :amount (gstring/format "%.2f"))]
      [:td {:align "right" :style {:padding-right "1em"}}
       (-> transaction :date (date/unixtime->prettydate))]
      [:td (:description transaction)]
      (if (-> transaction :category-id some?)
        [:td {:on-click #(dispatch [:view-transaction-match transaction])} "View"]
        [:td ""])
      ;; (if (-> transaction :category-id nil?)
      ;;   [:div {:id "multi-dropdown"
      ;;          :class "dropdown inline-block relative"
      ;;             ;;  :class "z-10 hidden bg-white divide-y divide-gray-100 rounded-lg shadow w-44 dark:bg-gray-700"
      ;;          }
      ;;    [:button ;.bg-gray-300.text-gray-700.font-semibold.py-0.px-4.inline-flex.items-center.text-sm
      ;;     {:type "button" :data-dropdown-toggle "multi-dropdown"
      ;;      :class "text-white bg-blue-700 hover:bg-blue-800 focus:ring-4 focus:outline-none focus:ring-blue-300 font-medium rounded-lg text-sm px-3 py-2.5 text-center inline-flex items-center dark:bg-blue-600 dark:hover:bg-blue-700 dark:focus:ring-blue-800"}
      ;;     "Legg til " (menu-angle)]
      ;;    (if (nil? builder-category)
      ;;      (add-category-menu categories (:description transaction))
      ;;      (add-category-menu-edit (:description transaction)))]
      ;; [:td ""])
      ]

     (when is-editing?
       (build-transaction-row-editor transaction transaction-row-editor category-map))]))

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
