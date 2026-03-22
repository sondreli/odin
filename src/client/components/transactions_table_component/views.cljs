(ns client.components.transactions-table-component.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [common.category-service :as category]
            [goog.string :as gstring]
            [client.services.date-service :as date]
            [client.services.color-service :as color]
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
    (assoc props :disabled "disabled")
    props))

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

(defn filter-statistics-component
  [stats show-categorized? show-uncategorized? category-map]
  (when stats
    [:tr {:key "filter-stats"}
     [:td {:col-span 6
           :style {:padding "8px" :background-color "#f8f9fa"
                   :font-size "12px" :border-top "2px solid #dee2e6"}}
      [:div {:style {:display "flex" :gap "20px" :justify-content "center" :align-items "center"}}
       [:span {:style {:font-weight "bold" :color "#495057"}} "Filter Statistics:"]
       [:span {:style {:color "#28a745" :cursor "pointer" :text-decoration "underline"}
               :on-click #(dispatch [:toggle-uncategorized-transactions])}
        (str "Uncategorized: " (:uncategorized stats))]
       [:span {:style {:color "#007bff" :cursor "pointer" :text-decoration "underline"}
               :on-click #(dispatch [:toggle-categorized-transactions])}
        (str "Categorized: " (:categorized stats))]]
      (when (and show-uncategorized? (> (:uncategorized stats) 0))
        [:div {:style {:margin-top "10px" :padding "10px"
                       :background-color "white" :border "1px solid #dee2e6"}}
         [:h4 {:style {:margin "0 0 10px 0" :color "#28a745"}} "Uncategorized Transactions:"]
         [:table {:style {:width "100%" :font-size "11px"}}
          [:thead
           [:tr
            [:th {:style {:text-align "right" :padding "2px 5px"}} "Amount"]
            [:th {:style {:text-align "left" :padding "2px 5px"}} "Date"]
            [:th {:style {:text-align "left" :padding "2px 5px"}} "Description"]]]
          [:tbody
           (for [[idx txn] (map-indexed vector (:uncategorized-transactions stats))]
             [:tr {:key idx}
              [:td {:style {:text-align "right" :padding "2px 5px"}}
               (gstring/format "%.2f" (:amount txn))]
              [:td {:style {:padding "2px 5px"}}
               (date/unixtime->prettydate (:date txn))]
              [:td {:style {:padding "2px 5px"}} (:description txn)]])]]])
      (when (and show-categorized? (> (:categorized stats) 0))
        [:div {:style {:margin-top "10px" :padding "10px"
                       :background-color "white" :border "1px solid #dee2e6"}}
         [:h4 {:style {:margin "0 0 10px 0" :color "#007bff"}} "Categorized Transactions:"]
         [:table {:style {:width "100%" :font-size "11px"}}
          [:thead
           [:tr
            [:th {:style {:text-align "right" :padding "2px 5px"}} "Amount"]
            [:th {:style {:text-align "left" :padding "2px 5px"}} "Date"]
            [:th {:style {:text-align "left" :padding "2px 5px"}} "Description"]
            [:th {:style {:text-align "left" :padding "2px 5px"}} "Category"]
            [:th {:style {:text-align "left" :padding "2px 5px"}} "Filter"]]]
          [:tbody
           (for [[idx txn] (map-indexed vector (:categorized-transactions stats))]
             (let [cat (get category-map (:category-id txn))
                   filter-line (when cat (category/find-sub-filter cat txn))]
               [:tr {:key idx :style {:background-color (when cat (:color cat))}}
                [:td {:style {:text-align "right" :padding "2px 5px"}}
                 (gstring/format "%.2f" (:amount txn))]
                [:td {:style {:padding "2px 5px"}}
                 (date/unixtime->prettydate (:date txn))]
                [:td {:style {:padding "2px 5px"}} (:description txn)]
                [:td {:style {:padding "2px 5px"}} (:name cat)]
                [:td {:style {:padding "2px 5px" :font-style "italic"}}
                 (or filter-line "—")]]))]]])]]))

(defn- transaction-tag-editor [transaction-index transaction]
  (let [tags @(subscribe [:tags])
        tag-map (into {} (map (juxt :id identity) tags))
        current-tag-ids (set (or (:tag-ids transaction) []))
        filter-tag-ids (set (or (:filter-tag-ids transaction) []))]
    [:tr {:key "transaction-tag-editor"}
     [:td {:col-span 6
           :style {:padding "6px 8px" :background-color "#f8f9fa"
                   :border-top "1px solid #eee"}}
      [:div {:style {:display "flex" :align-items "center" :gap "6px" :flex-wrap "wrap"}}
       [:span {:style {:font-size "11px" :color "#666" :font-weight "600"}} "Tags:"]
       (doall
        (for [tag tags]
          (let [selected? (contains? current-tag-ids (:id tag))
                from-filter? (contains? filter-tag-ids (:id tag))]
            ^{:key (:id tag)}
            [:label {:style {:display "inline-flex" :align-items "center" :gap "2px"
                             :padding "1px 8px" :font-size "11px" :cursor "pointer"
                             :border-radius "10px"
                             :border (str "1px solid " (if selected? "#333" "#ccc"))
                             :background-color (if selected?
                                                 (or (:color tag) "#e0e0e0")
                                                 "white")}}
             [:input {:type "checkbox"
                      :checked selected?
                      :on-change #(dispatch [:toggle-transaction-tag transaction-index (:id tag)])
                      :style {:width "11px" :height "11px" :margin 0}}]
             (:name tag)
             (when from-filter?
               [:span {:style {:font-size "9px" :color "#888" :margin-left "2px"}} "(F)"])])))]]]))

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
                           (-> init handle-category handle-checked))
        filter-stats (:filter-statistics transaction-row-editor)
        show-categorized? (:show-categorized-transactions? transaction-row-editor)
        show-uncategorized? (:show-uncategorized-transactions? transaction-row-editor)
        has-categorized-matches? (and (some? filter-stats) (> (:categorized filter-stats) 0))
        all-matches-same-category? (and has-categorized-matches?
                                        (some? category)
                                        (= (:categorized filter-stats)
                                           (:same-category filter-stats)))
        store-button-disabled? (and has-categorized-matches?
                                   (not all-matches-same-category?))
        row-index (:row-index transaction-row-editor)]
    [[:tr {:key "transaction-row-editor"}
      [:td [:button (-> {:class "buttom-class"
                        :style {:opacity (if store-button-disabled? "0.5" "1")
                                :cursor (if store-button-disabled? "not-allowed" "pointer")}
                        :on-click #(dispatch [:update-transactions-step-one category transaction])}
                       (add-disabled store-button-disabled?)) "Lagre"]]
      [:td {:style {:width "12px" :min-width "12px" :padding 0
                    :background-color (or (and category (:color category)) "#e9ecef")}}]
      [:td (build-category-select category category-map)]
      [:td [:input checkbox-html] "filter: "]
      [:td [:input filter-input-html]]
      [:td (if (some? category)
             (if (and (or sub-filter is-match?) checked?)
               "marked by filter"
               "marked manually")
             "")]]
     (transaction-tag-editor row-index transaction)
     (filter-statistics-component filter-stats show-categorized? show-uncategorized? category-map)]))

(defn- transaction-tag-dots [transaction tag-map]
  (let [tag-ids (or (:tag-ids transaction) [])]
    (when (seq tag-ids)
      [:span {:style {:display "inline-flex" :gap "2px" :margin-left "4px"}}
       (doall
        (for [tid tag-ids]
          (when-let [tag (get tag-map tid)]
            ^{:key tid}
            [:span {:title (:name tag)
                    :style {:width "8px" :height "8px" :border-radius "50%"
                            :display "inline-block"
                            :background-color (or (:color tag) "#888")}}])))])))

(defn transaction-row [index transaction builder-category transaction-row-editor category-map]
  (let [is-editing? (and (some? transaction-row-editor)
                         (-> transaction-row-editor :row-index (= index)))
        category (when-let [cid (:category-id transaction)] (get category-map cid))
        color (or (and category (:color category)) "#e9ecef")
        tags @(subscribe [:tags])
        tag-map (into {} (map (juxt :id identity) tags))
        transaction-row-html [:tr {:key index :data-amount (:amount transaction)}
                              [:td [:a {:class "cursor-pointer" :on-click #(dispatch [:edit-transaction-row index])} (if is-editing? "Lukk" "Endre")]]
                              [:td {:style {:width "12px" :min-width "12px" :padding 0 :background-color color}}]
                              [:td {:align "right" :style {:padding-right "1em"}}
                               (->> transaction :amount (gstring/format "%.2f"))]
                              [:td {:align "right" :style {:padding-right "1em"}}
                               (-> transaction :date (date/unixtime->prettydate))]
                              [:td (if is-editing?
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
                                     [:span
                                      (:description transaction)
                                      [transaction-tag-dots transaction tag-map]])]
                              (if (and (:category-id transaction) (:marked-by-filter? transaction))
                                [:td [:a {:class "cursor-pointer" :on-click #(dispatch [:view-transaction-match transaction])} "View"]]
                                [:td ""])]]
    [(if is-editing?
       (concat [transaction-row-html] (build-transaction-row-editor transaction transaction-row-editor category-map))
       transaction-row-html)]))

(defn add-sort-sigil [column sort-column sort-order]
  (cond
    (and (= column sort-column)
         (= sort-order :reverse)) "▼"
    (= column sort-column) "▲"
    :else [:span {:style {:font-size "0.7em"}} "■"]))

(defn- tag-chip [tag on-remove]
  [:span {:style {:display "inline-flex" :align-items "center" :gap "2px"
                  :padding "1px 6px" :margin "1px"
                  :font-size "10px" :border-radius "8px"
                  :background-color (or (:color tag) "#e0e0e0")
                  :color "#333"}}
   (:name tag)
   (when on-remove
     [:a {:class "cursor-pointer"
          :style {:font-size "10px" :color "#666" :margin-left "2px"}
          :on-click on-remove}
      "×"])])

(defn- filter-tag-section [_filter-index _filter-obj _tags _expanded-tag-index]
  (let [new-tag-name (r/atom "")
        new-tag-color (r/atom "#6b7280")]
    (fn [filter-index filter-obj tags _expanded-tag-index]
      (let [filter-tag-ids (set (or (:tag-ids filter-obj) []))]
        [:div {:style {:padding "4px 0 4px 16px" :border-left "2px solid #ddd"
                       :margin "2px 0"}}
         [:div {:style {:display "flex" :flex-wrap "wrap" :gap "2px" :margin-bottom "4px"}}
          (doall
           (for [tag tags]
             (let [selected? (contains? filter-tag-ids (:id tag))]
               ^{:key (:id tag)}
               [:label {:style {:display "inline-flex" :align-items "center" :gap "2px"
                                :padding "1px 6px" :font-size "10px" :cursor "pointer"
                                :border-radius "8px"
                                :border (str "1px solid " (if selected? "#333" "#ccc"))
                                :background-color (if selected?
                                                    (or (:color tag) "#e0e0e0")
                                                    "white")}}
                [:input {:type "checkbox"
                         :checked selected?
                         :on-change #(dispatch [:toggle-filter-tag filter-index (:id tag)])
                         :style {:width "10px" :height "10px" :margin 0}}]
                (:name tag)])))]
         [:div {:style {:display "flex" :gap "4px" :align-items "center"}}
          [:input {:type "text"
                   :value @new-tag-name
                   :placeholder "Ny tag..."
                   :on-change #(reset! new-tag-name (-> % .-target .-value))
                   :style {:width "80px" :padding "1px 4px" :font-size "10px"
                           :border "1px solid #ccc" :border-radius "3px"}}]
          [:input {:type "color"
                   :value @new-tag-color
                   :on-change #(reset! new-tag-color (-> % .-target .-value))
                   :style {:width "20px" :height "18px" :padding 0
                           :border "1px solid #ccc" :cursor "pointer"}}]
          [:button {:on-click (fn []
                                (when (seq @new-tag-name)
                                  (dispatch [:store-tag {:name @new-tag-name
                                                         :color @new-tag-color}])
                                  (reset! new-tag-name "")
                                  (reset! new-tag-color "#6b7280")))
                    :style {:padding "1px 6px" :font-size "10px" :cursor "pointer"}}
           "+"]]
         [:button {:on-click #(dispatch [:save-filter-tags filter-index])
                   :style {:padding "1px 8px" :font-size "10px" :cursor "pointer"
                           :margin-top "4px"}}
          "Lagre tags"]]))))

(defn- sidebar-category-row-inner [_cat _builder-category _editing-txn _editing-filter-index]
  (let [expanded-tag-index (r/atom nil)]
    (fn [cat builder-category editing-txn editing-filter-index]
      (let [is-editing? (= (:id cat) (:id builder-category))
            desc        (when editing-txn (:description editing-txn))
            matching-lines (when (and desc (-> cat :marker :description seq))
                             (filterv #(category/match-fun desc %) (-> cat :marker :description)))
            has-match?  (and editing-txn (seq matching-lines))
            tags @(subscribe [:tags])
            tag-map (into {} (map (juxt :id identity) tags))
            filters (or (:filters builder-category) (:filters cat) [])]
        [:<>
         [:tr {:key (:id cat)
               :style (merge {}
                              (when has-match?
                                {:outline "2px solid #f59e0b" :outline-offset "-2px"}))}
          [:td [:a {:class "cursor-pointer"
                    :on-click #(do (reset! expanded-tag-index nil)
                                   (dispatch [:edit-sidebar-category (:id cat)]))}
                (if is-editing? "Lukk" "Endre")]]
          [:td {:style {:background-color (:color cat) :padding "2px 6px"}}
           (:name cat)]]
         (when is-editing?
           [:tr {:key (str (:id cat) "-editor")}
            [:td {:col-span 2 :style {:padding "4px" :background-color "#f0f0f0"}}
             (let [lines (vec (or (-> builder-category :marker :description) []))]
               [:div {:style {:display "flex" :flex-direction "column" :gap "2px"}}
                (doall
                 (for [[i line] (map-indexed vector lines)]
                   (let [line-matches? (and desc (seq line) (category/match-fun desc line))
                         is-active? (= i editing-filter-index)
                         filter-obj (get filters i)
                         filter-tag-ids (or (:tag-ids filter-obj) [])
                         tag-expanded? (= @expanded-tag-index i)]
                     ^{:key i}
                     [:div {:style {:display "flex" :flex-direction "column"}}
                      [:div {:style {:display "flex" :align-items "center" :gap "2px"}}
                       [:input {:type "text"
                                :value line
                                :data-filter-index i
                                :on-change #(dispatch [:update-filter-line i (-> % .-target .-value)])
                                :on-key-down (fn [e]
                                               (when (= (.-key e) "Enter")
                                                 (.preventDefault e)
                                                 (let [container (-> (.-target e) (.closest "td"))]
                                                   (dispatch [:add-filter-line])
                                                   (js/setTimeout
                                                    (fn []
                                                      (when container
                                                        (let [inputs (.querySelectorAll container "input[data-filter-index]")
                                                              last-input (aget inputs (dec (.-length inputs)))]
                                                          (when last-input (.focus last-input)))))
                                                    50))))
                                :style (merge {:flex "1" :padding "2px 4px"
                                               :font-size "12px" :border-radius "3px"
                                               :box-sizing "border-box"
                                               :border "1px solid #ccc"}
                                              (when line-matches?
                                                {:background-color "#fef3c7"})
                                              (when is-active?
                                                {:border-color "#f59e0b"
                                                 :outline "1px solid #f59e0b"}))}]
                       [:a {:class "cursor-pointer"
                            :style {:font-size "11px" :padding "0 2px"
                                    :color (if tag-expanded? "#2563eb" "#666")}
                            :on-click #(swap! expanded-tag-index
                                              (fn [cur] (if (= cur i) nil i)))}
                        (str "T" (when (seq filter-tag-ids)
                                   (str "(" (count filter-tag-ids) ")")))]
                       [:a {:class "cursor-pointer"
                            :style {:color "#c00" :font-size "14px" :line-height "1"
                                    :padding "0 2px"}
                            :on-click #(dispatch [:remove-filter-line i])}
                        "×"]]
                      (when (seq filter-tag-ids)
                        [:div {:style {:display "flex" :flex-wrap "wrap" :gap "1px"
                                       :padding-left "4px" :margin-top "1px"}}
                         (doall
                          (for [tid filter-tag-ids]
                            (when-let [tag (get tag-map tid)]
                              ^{:key tid}
                              [tag-chip tag nil])))])
                      (when tag-expanded?
                        [filter-tag-section i filter-obj tags expanded-tag-index])])))
                [:button {:on-click #(dispatch [:add-filter-line])
                          :style {:align-self "flex-start" :padding "1px 8px"
                                  :font-size "11px" :cursor "pointer"
                                  :margin-top "2px"}}
                 "+"]
                [:div {:style {:display "flex" :justify-content "space-between"
                              :align-items "center" :margin-top "4px"}}
                 [:button {:on-click #(dispatch [:store-category3])
                           :style {:padding "2px 10px" :font-size "12px" :cursor "pointer"}}
                  "Lagre"]
                 [:a {:class "cursor-pointer"
                      :style {:font-size "11px" :color "#c00"}
                      :on-click #(when (js/confirm (str "Slett kategori \"" (:name cat) "\"?"))
                                   (dispatch [:delete-category (:id cat)]))}
                  "Slett"]]])]])]))))

(defn- sidebar-category-row [& args]
  (into [sidebar-category-row-inner] args))

(def ^:private palette-colors
  (mapv #(-> [% 0.6 0.9]
             color/hsv2rgb
             color/color-base10->base16
             color/color-str)
        (color/generate-hues 16)))

(defn- color-palette [selected-color on-change]
  [:div {:style {:display "flex" :flex-wrap "wrap" :gap "3px" :margin "2px 0"}}
   (doall
    (for [[i c] (map-indexed vector palette-colors)]
      ^{:key i}
      [:div {:style {:width "22px" :height "22px" :border-radius "3px"
                     :background-color c :cursor "pointer"
                     :border (if (= c selected-color) "2px solid #333" "2px solid transparent")
                     :box-sizing "border-box"}
             :on-click #(on-change c)}]))])

(defn- new-category-editor [builder-category]
  (let [is-editing? (= "new-id" (str (:id builder-category)))]
    [:<>
     [:tr {:key "new-cat-btn"}
      [:td {:col-span 2}
       [:a {:class "cursor-pointer"
            :style {:font-size "12px" :color "#333"}
            :on-click #(dispatch [:edit-sidebar-category "new-id"])}
        (if is-editing? "Avbryt" "+ Ny kategori")]]]
     (when is-editing?
       [:tr {:key "new-cat-editor"}
        [:td {:col-span 2 :style {:padding "4px" :background-color "#f0f0f0"}}
         [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
          [:input {:type "text" :placeholder "Navn"
                   :value (:name builder-category)
                   :on-change #(dispatch [:update-builder-category-name (-> % .-target .-value)])
                   :style {:padding "4px 6px" :font-size "12px"
                           :border "1px solid #ccc" :border-radius "3px"}}]
          [color-palette
           (or (:color builder-category) "#5ce67e")
           #(dispatch [:update-builder-category-color %])]
          [:button {:on-click #(dispatch [:store-category3])
                    :style {:padding "4px 10px" :font-size "12px" :cursor "pointer"
                            :background-color "#333" :color "#fff"
                            :border "none" :border-radius "4px"}}
           "Lagre"]]]])]))

(defn- categories-sidebar [categories editing-txn editing-filter-index]
  (let [builder-category @(subscribe [:builder-category])
        cats (->> categories (filter :name))]
    [:div {:style {:width "250px" :min-width "250px"
                   :position "sticky" :top "20vh"
                   :align-self "flex-start"
                   :max-height "80vh" :overflow-y "auto"
                   :border-left "1px solid #ddd" :padding-left "8px"
                   :font-size "13px"}}
     [:h4 {:style {:margin "0 0 6px 0"}} "Kategorier"]
     [:table {:style {:width "100%"}}
      [:tbody
       (doall
        (for [cat cats]
          ^{:key (:id cat)}
          [sidebar-category-row cat builder-category editing-txn editing-filter-index]))
       [new-category-editor builder-category]]]]))

(defn- compute-selection-sum []
  (let [selection (.getSelection js/window)
        sel-str (when selection (.toString selection))]
    (when (and sel-str (pos? (count sel-str)) (pos? (.-rangeCount selection)))
      (let [range (.getRangeAt selection 0)
            tbody (.getElementById js/document "transactions-tbody")]
        (when tbody
          (let [rows (array-seq (.querySelectorAll tbody "tr[data-amount]"))
                selected-rows (filterv #(.intersectsNode range %) rows)
                amounts (mapv #(js/parseFloat (.getAttribute % "data-amount")) selected-rows)
                valid-amounts (filterv #(not (js/isNaN %)) amounts)]
            (when (>= (count valid-amounts) 2)
              (let [last-row (last selected-rows)
                    rect (.getBoundingClientRect last-row)]
                {:sum (reduce + 0 valid-amounts)
                 :count (count valid-amounts)
                 :bottom (+ (.-bottom rect) 4)
                 :left (.-left rect)}))))))))

(defn- selection-sum-popup [data]
  (when data
    [:div {:style {:position "fixed"
                   :top (str (:bottom data) "px")
                   :left (str (:left data) "px")
                   :background-color "#1a1a2e"
                   :color "white"
                   :padding "6px 14px"
                   :border-radius "6px"
                   :font-size "13px"
                   :font-weight "500"
                   :box-shadow "0 2px 8px rgba(0,0,0,0.25)"
                   :z-index 1000
                   :pointer-events "none"
                   :white-space "nowrap"}}
     [:span "Sum: " (gstring/format "%.2f" (:sum data))]
     [:span {:style {:margin-left "12px" :opacity "0.7" :font-size "11px"}}
      (str "(" (:count data) " rader)")]]))

(def ^:private selection-sum-state (r/atom nil))

(defn transactions-table [displayed-transactions-data categories]
  (let [builder-category @(subscribe [:builder-category])
        transaction-row-editor @(subscribe [:transaction-row-editor])
        transactions (:displayed-transactions displayed-transactions-data)
        sort-column (-> displayed-transactions-data :sort-column)
        sort-order (-> displayed-transactions-data :sort-order)
        indexed-transactions (map-indexed vector transactions)
        category-map (into {} (map (juxt :id #(identity %)) categories))
        rows (mapcat (fn [[index transaction]] (transaction-row index transaction builder-category transaction-row-editor category-map)) indexed-transactions)
        editing-txn (when-let [idx (:row-index transaction-row-editor)]
                      (get transactions idx))
        editing-filter-index (:editing-filter-index transaction-row-editor)]
    [:div {:style {:display "flex" :gap "12px"}
           :on-mouse-down #(reset! selection-sum-state nil)
           :on-mouse-up (fn [_]
                           (js/setTimeout
                            (fn [] (reset! selection-sum-state (compute-selection-sum)))
                            10))}
     [:div {:style {:flex "1" :min-width "0" :overflow-x "auto"}}
      [:table
       [:tr
        [:th ""]
        [:th {:style {:width "12px" :min-width "12px" :padding 0}}]
        [:th {:on-click #(dispatch [:sort-column :amount])} (add-sort-sigil :amount sort-column sort-order)]
        [:th {:on-click #(dispatch [:sort-column :date])} (add-sort-sigil :date sort-column sort-order)]
        [:th {:on-click #(dispatch [:sort-column :description])} (add-sort-sigil :description sort-column sort-order)]
        [:th ""]]
       [:tbody {:id "transactions-tbody"}
        rows]]
      [selection-sum-popup @selection-sum-state]]
     [categories-sidebar categories editing-txn editing-filter-index]]))
