(ns client.components.transactions-table-component.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [common.category-service :as category]
            [goog.string :as gstring]
            [client.services.date-service :as date]
            [client.services.color-service :as color]
            [client.components.treemap-component.views :as treemap]
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
  (let [categories-select (->> category-map
                               (filter (fn [[_ cat]] (and (:name cat) (not= (:name cat) ""))))
                               (into [])
                               (sort-by #(first %)))
        selected-id (when (some? current-category)
                      (some (fn [[cid cat]] (when (= (:name cat) (:name current-category)) cid)) categories-select))
        category-options (map (fn [[category-id cat]] [:option (if (= category-id selected-id)
                                                                 {:key category-id :value category-id :selected "selected"}
                                                                 {:key category-id :value category-id}) (:name cat)]) categories-select)]
    [:select {:style {:border "1px solid #ccc" :border-radius "3px" :padding "2px 4px" :height "26px" :box-sizing "border-box"
                      :color (if selected-id "inherit" "#999")}
              :on-change #(dispatch [:select-new-category (-> % .-target .-value)])}
     (concat [[:option {:key " " :value " " :style {:color "#999"}} "Kategori"]] category-options)]))

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
                   :font-size "11px" :border-top "2px solid #dee2e6"}}
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
        category (if (contains? transaction-row-editor :new-category)
                    (if (-> transaction-row-editor :new-category some?)
                      (get category-map (-> transaction-row-editor :new-category))
                      nil)
                    (if (-> category-map (get category-id) some?)
                      (-> category-map (get category-id))
                      nil))
        not-category? (nil? category)
        sub-filter (category/find-sub-filter category transaction)
        new-sub-filter (-> transaction-row-editor :new-sub-filter)
        is-match? (-> transaction-row-editor :is-match?)
        filter-value (if (some? new-sub-filter) new-sub-filter sub-filter)
        filter-has-value? (and (some? filter-value) (not= filter-value ""))
        filter-input-html (let [init {:type "text" :value filter-value :placeholder "Filter"
                                      :style {:border "1px solid #ccc" :border-radius "3px" :padding "2px 4px" :height "26px" :box-sizing "border-box"}
                                      :on-change #(dispatch [:mark-transaction (-> % .-target .-value)])}]
                            (if not-category? (assoc init :disabled true) init))
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
      [:td {:col-span 6 :style {:padding "6px 8px" :background-color "#f8f9fa"}}
       [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
        [:button (-> {:class "buttom-class"
                      :style {:opacity (if store-button-disabled? "0.5" "1")
                              :cursor (if store-button-disabled? "not-allowed" "pointer")}
                      :on-click #(dispatch [:update-transactions-step-one category transaction])}
                     (add-disabled store-button-disabled?)) "Lagre"]
        [:div {:style {:width "12px" :height "12px" :border-radius "2px"
                       :background-color (or (and category (:color category)) "#e9ecef")}}]
        (build-category-select category category-map)
        [:input filter-input-html]
        [:span {:style {:font-size "12px" :color "#666" :white-space "nowrap"}}
         (if (some? category)
           (if filter-has-value?
             "marked by filter"
             "marked manually")
           "")]]]]
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

(defn transaction-row [index transaction builder-category transaction-row-editor category-map multi-select]
  (let [is-editing? (and (some? transaction-row-editor)
                         (-> transaction-row-editor :row-index (= index)))
        multi-select-mode? (some? multi-select)
        is-selected? (and multi-select-mode? (contains? (:selected-indices multi-select) index))
        category (when-let [cid (:category-id transaction)] (get category-map cid))
        color (or (and category (:color category)) "#e9ecef")
        tags @(subscribe [:tags])
        tag-map (into {} (map (juxt :id identity) tags))
        any-editing? (some? transaction-row-editor)
        period @(subscribe [:period])
        single-month? (= (:period-type period) :month)
        hovered-cat (when single-month? @treemap/hovered-category-id)
        effective-cat-id (or (:category-id transaction)
                             (if (neg? (:amount transaction))
                               "ukategorisert-out"
                               "ukategorisert-in"))
        hovered-fade? (and (some? hovered-cat)
                           (not= hovered-cat effective-cat-id))
        transaction-row-html [:tr {:key index :data-amount (:amount transaction)
                                   :style (merge
                                           (when (and any-editing? (not is-editing?))
                                             {:opacity "0.4"})
                                           (when hovered-fade?
                                             {:opacity "0.4" :transition "opacity 0.15s ease"})
                                           (when is-selected?
                                             {:background-color "#e8f0fe"}))}
                              [:td (if multi-select-mode?
                                     [:input {:type "checkbox" :checked is-selected?
                                              :on-change #(dispatch [:toggle-multi-select-row index])
                                              :style {:cursor "pointer"}}]
                                     [:a {:class "cursor-pointer" :on-click #(dispatch [:edit-transaction-row index])} (if is-editing? "Lukk" "Endre")])]
                              [:td {:style {:width "20px" :min-width "20px" :padding 0 :background-color color}}]
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
    :else "△"))

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
         [:button {:on-click #(dispatch [:save-filter-tags filter-index])
                   :style {:padding "1px 8px" :font-size "10px" :cursor "pointer"
                           :margin-top "4px"}}
          "Lagre tags"]])))

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
          [:td {:style {:background-color (:color cat) :padding "2px 6px" :cursor "pointer"}
                :on-click #(dispatch [:view-category (:name cat)])}
           (:name cat)]]
         (when is-editing?
           [:tr {:key (str (:id cat) "-editor")}
            [:td {:col-span 2 :style {:padding "4px" :background-color "#f8f9fa"
                                       :border-left (str "3px solid " (or (:color cat) "#ccc"))}}
             (let [lines (vec (or (-> builder-category :marker :description) []))]
               [:div {:style {:display "flex" :flex-direction "column" :gap "2px"}}
                [:span {:style {:font-size "10px" :font-weight "bold" :color "#888"
                                :text-transform "uppercase" :letter-spacing "0.5px"
                                :margin-bottom "2px"}} "Filtre"]
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
                [:div {:style {:border-top "1px solid #ddd" :margin-top "6px" :padding-top "6px"
                              :display "flex" :justify-content "space-between"
                              :align-items "center"}}
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

(defn- color-palette [selected-color on-change used-colors]
  [:div {:style {:display "flex" :flex-wrap "wrap" :gap "3px" :margin "2px 0"}}
   (doall
    (for [[i c] (map-indexed vector palette-colors)]
      ^{:key i}
      [:div {:style {:width "22px" :height "22px" :border-radius "3px"
                     :background-color c :cursor "pointer"
                     :border (if (= c selected-color) "2px solid #333" "2px solid transparent")
                     :box-sizing "border-box"
                     :display "flex" :align-items "center" :justify-content "center"}
             :on-click #(on-change c)}
       (when (contains? used-colors c)
         [:div {:style {:width "8px" :height "8px" :border-radius "50%"
                        :background-color "rgba(0,0,0,0.4)"}}])]))])

(defn- new-category-editor [builder-category used-colors]
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
        [:td {:col-span 2 :style {:padding "4px" :background-color "#f8f9fa"}}
         [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
          [:input {:type "text" :placeholder "Navn"
                   :value (:name builder-category)
                   :on-change #(dispatch [:update-builder-category-name (-> % .-target .-value)])
                   :style {:padding "4px 6px" :font-size "12px"
                           :border "1px solid #ccc" :border-radius "3px"}}]
          [color-palette
           (or (:color builder-category) "#5ce67e")
           #(dispatch [:update-builder-category-color %])
           used-colors]
          [:button {:on-click #(dispatch [:store-category3])
                    :style {:padding "4px 10px" :font-size "12px" :cursor "pointer"
                            :background-color "#333" :color "#fff"
                            :border "none" :border-radius "4px"}}
           "Lagre"]]]])]))

(defn- tags-sidebar [categories]
  (let [tags @(subscribe [:tags])
        expanded (r/atom nil)
        new-tag-name (r/atom "")
        new-tag-color (r/atom "#6b7280")]
    (fn [categories]
      (let [tags @(subscribe [:tags])
            selected-tag-id @(subscribe [:selected-tag-id])
            cats (->> categories (filter :name))]
        [:div {:style {:margin-top "16px"}}
         [:h4 {:style {:margin "0 0 6px 0"}} "Tags"]
         [:table {:style {:width "100%"}}
          [:tbody
           (doall
            (for [tag tags]
              (let [tag-id (:id tag)
                    is-expanded? (= @expanded tag-id)
                    matching-filters (when is-expanded?
                                      (->> cats
                                           (mapcat (fn [cat]
                                                     (->> (or (:filters cat) [])
                                                          (filter #(some #{tag-id} (:tag-ids %)))
                                                          (map #(assoc % :category-name (:name cat)
                                                                         :category-color (:color cat))))))
                                           vec))]
                ^{:key tag-id}
                [:<>
                 [:tr
                  [:td {:style {:padding "2px 4px"}}
                   [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
                    [:a {:class "cursor-pointer"
                         :style {:font-size "11px"}
                         :on-click #(swap! expanded (fn [cur] (if (= cur tag-id) nil tag-id)))}
                     (if is-expanded? "▼" "▶")]
                    [:span {:style {:display "inline-block" :width "10px" :height "10px"
                                    :border-radius "50%" :background-color (or (:color tag) "#888")}}]
                    [:span {:style (merge {:cursor "pointer"}
                                          (when (= selected-tag-id tag-id)
                                            {:font-weight "bold" :text-decoration "underline"}))
                            :on-click #(dispatch [:select-tag tag-id])}
                     (:name tag)]
                    [:a {:class "cursor-pointer"
                         :style {:margin-left "auto" :font-size "11px" :color "#c00"}
                         :on-click #(when (js/confirm (str "Slett tag \"" (:name tag) "\"?"))
                                      (dispatch [:delete-tag tag-id]))}
                     "×"]]]]
                 (when is-expanded?
                   [:tr
                    [:td {:style {:padding "2px 8px 6px 20px" :background-color "#f8f9fa"}}
                     (if (seq matching-filters)
                       [:div {:style {:display "flex" :flex-direction "column" :gap "2px"}}
                        (doall
                         (for [[i f] (map-indexed vector matching-filters)]
                           ^{:key i}
                           [:div {:style {:display "flex" :align-items "center" :gap "4px" :font-size "11px"}}
                            [:span {:style {:display "inline-block" :width "8px" :height "8px"
                                            :border-radius "2px" :background-color (or (:category-color f) "#ccc")}}]
                            [:span {:style {:color "#666"}} (:category-name f)]
                            [:span {:style {:font-style "italic"}} (:text f)]]))]
                       [:span {:style {:font-size "11px" :color "#999"}} "Ingen filtre"])]])])))
           (let [is-creating? (= @expanded :new-tag)]
             [:<>
              [:tr {:key "new-tag-btn"}
               [:td
                [:a {:class "cursor-pointer"
                     :style {:font-size "12px" :color "#333"}
                     :on-click #(swap! expanded (fn [cur] (if (= cur :new-tag) nil :new-tag)))}
                 (if is-creating? "Avbryt" "+ Ny tag")]]]
              (when is-creating?
                [:tr {:key "new-tag-editor"}
                 [:td {:style {:padding "4px" :background-color "#f8f9fa"}}
                  [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
                   [:input {:type "text" :placeholder "Navn"
                            :value @new-tag-name
                            :on-change #(reset! new-tag-name (-> % .-target .-value))
                            :style {:padding "4px 6px" :font-size "12px"
                                    :border "1px solid #ccc" :border-radius "3px"}}]
                   [color-palette
                    @new-tag-color
                    #(reset! new-tag-color %)]
                   [:button {:on-click (fn []
                                         (when (seq @new-tag-name)
                                           (dispatch [:store-tag {:name @new-tag-name
                                                                  :color @new-tag-color}])
                                           (reset! new-tag-name "")
                                           (reset! new-tag-color "#6b7280")
                                           (reset! expanded nil)))
                             :style {:padding "4px 10px" :font-size "12px" :cursor "pointer"
                                     :background-color "#333" :color "#fff"
                                     :border "none" :border-radius "4px"}}
                    "Lagre"]]]])])]]]))))


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
       [new-category-editor builder-category (into #{} (keep :color cats))]]]
     [tags-sidebar categories]]))

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

(defn- multi-select-editor [multi-select transactions category-map categories]
  (let [{:keys [selected-indices editing? new-category new-tag-ids]} multi-select
        selected-count (count selected-indices)
        selected-txns (mapv #(get transactions %) (sort selected-indices))
        all-uncategorized? (every? #(nil? (:category-id %)) selected-txns)
        tags @(subscribe [:tags])
        tag-ids (or new-tag-ids #{})]
    (when (pos? selected-count)
      [:tr {:key "multi-select-editor"}
       [:td {:col-span 6 :style {:padding "8px" :background-color "#f8f9fa" :border-top "2px solid #dee2e6"}}
        (if-not editing?
          [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
           [:span {:style {:font-weight "600" :font-size "12px"}}
            (str selected-count " valgt")]
           [:button {:on-click #(dispatch [:multi-select-edit])
                     :style {:padding "4px 12px" :font-size "12px" :cursor "pointer"
                             :background-color "#333" :color "#fff"
                             :border "none" :border-radius "4px"}}
            "Rediger"]
           [:button {:on-click #(dispatch [:toggle-multi-select-mode])
                     :style {:padding "4px 12px" :font-size "12px" :cursor "pointer"
                             :border "1px solid #ccc" :border-radius "4px" :background-color "white"}}
            "Avbryt"]
           [:span {:style {:font-size "11px" :color "#888" :font-style "italic"}}
            (if all-uncategorized?
              "Kategori og tags kan settes"
              "Kun tags kan settes (noen har kategori)")]]
          [:div {:style {:display "flex" :flex-direction "column" :gap "8px"}}
           [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
            [:span {:style {:font-weight "600" :font-size "12px"}}
             (str selected-count " valgt")]
            (when all-uncategorized?
              [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
               [:span {:style {:font-size "13px" :color "#666"}} "Kategori:"]
               (let [cats (->> categories (filter :name) (sort-by :name))
                     selected-id (or new-category "")]
                 [:select {:style {:border "1px solid #ccc" :border-radius "3px" :padding "2px 4px"
                                   :height "26px" :box-sizing "border-box"
                                   :color (if (seq selected-id) "inherit" "#999")}
                           :value selected-id
                           :on-change #(dispatch [:multi-select-set-category (-> % .-target .-value)])}
                  [:option {:value ""} "Velg kategori"]
                  (for [cat cats]
                    ^{:key (:id cat)}
                    [:option {:value (:id cat)} (:name cat)])])])
            (when-not all-uncategorized?
              [:span {:style {:font-size "11px" :color "#999" :font-style "italic"}}
               "Kategori kan ikke settes (noen rader har allerede kategori)"])]
           [:div {:style {:display "flex" :flex-wrap "wrap" :gap "3px" :align-items "center"}}
            [:span {:style {:font-size "13px" :color "#666"}} "Tags:"]
            (doall
             (for [tag tags]
               (let [selected? (contains? tag-ids (:id tag))]
                 ^{:key (:id tag)}
                 [:label {:style {:display "inline-flex" :align-items "center" :gap "2px"
                                  :padding "2px 8px" :font-size "11px" :cursor "pointer"
                                  :border-radius "10px"
                                  :border (str "1px solid " (if selected? "#333" "#ccc"))
                                  :background-color (if selected? (or (:color tag) "#e0e0e0") "white")}}
                  [:input {:type "checkbox" :checked selected?
                           :on-change #(dispatch [:multi-select-toggle-tag (:id tag)])
                           :style {:width "11px" :height "11px" :margin 0}}]
                  (:name tag)])))]
           [:div {:style {:display "flex" :gap "6px" :margin-top "4px"}}
            [:button {:on-click #(dispatch [:multi-select-save])
                      :disabled (and (nil? new-category) (empty? tag-ids))
                      :style {:padding "4px 12px" :font-size "12px" :cursor "pointer"
                              :background-color "#333" :color "#fff"
                              :border "none" :border-radius "4px"
                              :opacity (if (and (nil? new-category) (empty? tag-ids)) "0.5" "1")}}
             "Lagre"]
            [:button {:on-click #(dispatch [:multi-select-cancel-edit])
                      :style {:padding "4px 12px" :font-size "12px" :cursor "pointer"
                              :border "1px solid #ccc" :border-radius "4px" :background-color "white"}}
             "Avbryt"]]])]])))

(defn transactions-table [displayed-transactions-data categories]
  (let [builder-category @(subscribe [:builder-category])
        transaction-row-editor @(subscribe [:transaction-row-editor])
        multi-select @(subscribe [:multi-select])
        multi-select-mode? (some? multi-select)
        transactions (:displayed-transactions displayed-transactions-data)
        sort-column (-> displayed-transactions-data :sort-column)
        sort-order (-> displayed-transactions-data :sort-order)
        indexed-transactions (map-indexed vector transactions)
        category-map (into {} (map (juxt :id #(identity %)) categories))
        rows (mapcat (fn [[index transaction]] (transaction-row index transaction builder-category transaction-row-editor category-map multi-select)) indexed-transactions)
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
        [:th {:style {:cursor "pointer" :font-size "11px"}
              :on-click #(dispatch [:toggle-multi-select-mode])}
         (if multi-select-mode? "Avbryt" "Velg")]
        [:th {:style {:width "12px" :min-width "12px" :padding 0}}]
        [:th {:style {:cursor "pointer" :text-align "right" :padding-right "1em"} :on-click #(dispatch [:sort-column :amount])}
         (add-sort-sigil :amount sort-column sort-order)]
        [:th {:style {:cursor "pointer" :text-align "right" :padding-right "1em"} :on-click #(dispatch [:sort-column :date])}
         (add-sort-sigil :date sort-column sort-order)]
        [:th {:style {:cursor "pointer" :text-align "right"} :on-click #(dispatch [:sort-column :description])}
         (add-sort-sigil :description sort-column sort-order)]
        [:th ""]]
       [:tbody {:id "transactions-tbody"}
        (when multi-select-mode?
          (multi-select-editor multi-select (vec transactions) category-map categories))
        rows]]
      [selection-sum-popup @selection-sum-state]]
     [categories-sidebar categories editing-txn editing-filter-index]]))
