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
  "Highlight a substring inside `text`. Uses the .hl class from friendly.css.
   When `filter-text` starts with the `regex:` prefix used by category-service/match-fun,
   the body is treated as a regex (case-insensitive) and the first match is highlighted."
  (cond
    (or (nil? filter-text) (= filter-text "") (nil? text))
    text

    (and (>= (count filter-text) 6)
         (= (subs filter-text 0 6) "regex:"))
    (let [pattern (subs filter-text 6)
          re (try (js/RegExp. pattern "i") (catch :default _ nil))
          m  (when re (.match text re))]
      (if (and m (> (.-length m) 0))
        (let [match-text (aget m 0)
              start-idx  (.-index m)
              end-idx    (+ start-idx (count match-text))]
          [:span
           (subs text 0 start-idx)
           [:mark.hl match-text]
           (subs text end-idx)])
        text))

    (s/includes? (s/lower-case text) (s/lower-case filter-text))
    (let [lower-text   (s/lower-case text)
          lower-filter (s/lower-case filter-text)
          start-idx    (.indexOf lower-text lower-filter)
          end-idx      (+ start-idx (count filter-text))]
      [:span
       (subs text 0 start-idx)
       [:mark.hl (subs text start-idx end-idx)]
       (subs text end-idx)])

    :else text))

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
        current-tag-ids (set (or (:tag-ids transaction) []))
        filter-tag-ids (set (or (:filter-tag-ids transaction) []))]
    [:div {:style {:display "flex" :align-items "center" :gap "6px" :flex-wrap "wrap"
                   :border-top "1px solid var(--border-soft)" :padding-top "8px"}}
       [:span {:style {:font-size "11px" :color "var(--text-dim)" :font-weight "600"}} "Tags:"]
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
               [:span {:style {:font-size "9px" :color "#888" :margin-left "2px"}} "(F)"])])))]))

(declare filter-builder-inline)

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
      [:td {:col-span 6 :style {:padding "6px 8px"}}
       [:div.txn-edit-panel
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
            "")]]
        (transaction-tag-editor row-index transaction)
        (when (some? category)
          [filter-builder-inline transaction-row-editor transaction category-map])]]]]))

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
        amt (:amount transaction)
        search @(subscribe [:transactions-search])
        transaction-row-html [:tr {:key index :data-amount amt
                                   :class (str "txn-row"
                                               (when is-selected? " is-selected"))
                                   :style (merge
                                           (when (and any-editing? (not is-editing?))
                                             {:opacity "0.4"})
                                           (when hovered-fade?
                                             {:opacity "0.4" :transition "opacity 0.15s ease"}))}
                              [:td (if multi-select-mode?
                                     [:input {:type "checkbox" :checked is-selected?
                                              :on-change #(dispatch [:toggle-multi-select-row index])
                                              :style {:cursor "pointer"}}]
                                     [:a {:class "cursor-pointer sb-edit"
                                          :on-click #(dispatch [:edit-transaction-row index])}
                                      (if is-editing? "Lukk" "Endre")])]
                              [:td.txn-cat-cell
                               [:span.cat-swatch.lg {:style {:background-color color}}]]
                              [:td {:class (str "txn-amount" (when (pos? amt) " pos"))}
                               (gstring/format "%.2f" amt)]
                              [:td.txn-date
                               (-> transaction :date (date/unixtime->prettydate))]
                              [:td.txn-desc (if is-editing?
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
                                      (highlight-text (:description transaction) search)
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
  (let [expanded-tag-index (r/atom nil)
        scrolled-for (r/atom nil)]
    (fn [cat builder-category editing-txn editing-filter-index]
      (let [is-editing? (= (:id cat) (:id builder-category))
            desc        (when editing-txn (:description editing-txn))
            matching-lines (when (and desc (-> cat :marker :description seq))
                             (filterv #(category/match-fun desc %) (-> cat :marker :description)))
            has-match?  (and editing-txn (seq matching-lines))
            tags @(subscribe [:tags])
            tag-map (into {} (map (juxt :id identity) tags))
            filters (or (:filters builder-category) (:filters cat) [])
            period-txns @(subscribe [:period-transactions])
            cat-count (count (filter #(= (:id cat) (:category-id %)) period-txns))]
        [:<>
         [:tr {:key (:id cat)
               :class (str "txn-row" (when has-match? " has-match"))
               :style (when has-match?
                        {:outline "2px solid var(--c-accent)"
                         :outline-offset "-2px"
                         :border-radius "8px"})}
          [:td {:style {:width "44px"}}
           [:button.sb-edit
            {:on-click #(do (reset! expanded-tag-index nil)
                            (dispatch [:edit-sidebar-category (:id cat)]))}
            (if is-editing? "Lukk" "Endre")]]
          [:td {:style {:cursor "pointer"}
                :on-click #(dispatch [:view-category (:name cat)])}
           [:span.sb-name
            [:span.cat-swatch {:style {:background-color (:color cat)}}]
            [:span (:name cat)]]]
          [:td {:style {:text-align "right" :width "40px"}}
           [:span.sb-count cat-count]]]
         (when is-editing?
           [:tr {:key (str (:id cat) "-editor")}
            [:td {:col-span 3 :style {:padding 0}}
             (let [lines (vec (or (-> builder-category :marker :description) []))
                   edit-key (when editing-txn
                              [(:date editing-txn) (:description editing-txn) (:id cat)])]
               [:div {:class (str "sb-edit-panel" (when has-match? " has-match"))}
                [:div {:style {:font-size "11px" :font-weight "700"
                               :color "var(--text-dim)" :margin-bottom "2px"}}
                 "Filtre"]
                (doall
                 (for [[i line] (map-indexed vector lines)]
                   (let [line-matches? (and desc (seq line) (category/match-fun desc line))
                         is-active? (= i editing-filter-index)
                         filter-obj (get filters i)
                         filter-tag-ids (or (:tag-ids filter-obj) [])
                         tag-expanded? (= @expanded-tag-index i)]
                     ^{:key i}
                     [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
                      [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
                       [:input.sb-filter-input
                        {:type "text"
                         :value line
                         :data-filter-index i
                         :ref (when line-matches?
                                (fn [el]
                                  (when (and el (not= @scrolled-for edit-key))
                                    (reset! scrolled-for edit-key)
                                    (js/setTimeout
                                     (fn []
                                       ;; Scroll ONLY the sidebar's internal
                                       ;; overflow, never the page. scrollIntoView
                                       ;; walks up and scrolls every scrollable
                                       ;; ancestor, which would shift the
                                       ;; transactions table — so do it manually.
                                       (when-let [panel (.closest el ".sidebar-panel")]
                                         (let [er (.getBoundingClientRect el)
                                               pr (.getBoundingClientRect panel)
                                               rel-top (- (.-top er) (.-top pr))
                                               target (+ (.-scrollTop panel)
                                                         rel-top
                                                         (- (/ (.-clientHeight panel) 2))
                                                         (/ (.-height er) 2))]
                                           (.scrollTo panel
                                            #js {:top target :behavior "smooth"}))))
                                     0))))
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
                         :style (merge {:flex "1"}
                                       (when line-matches?
                                         {:background-color "var(--highlight)"})
                                       (when is-active?
                                         {:border-color "var(--c-accent)"
                                          :outline "1px solid var(--c-accent)"}))}]
                       [:button.sb-edit
                        {:style (merge {:min-width "auto"}
                                       (when tag-expanded? {:color "var(--c-accent)"}))
                         :on-click #(swap! expanded-tag-index
                                           (fn [cur] (if (= cur i) nil i)))}
                        (str "T" (when (seq filter-tag-ids)
                                   (str "(" (count filter-tag-ids) ")")))]
                       [:button.sb-edit
                        {:style {:color "var(--c-down)" :min-width "auto"}
                         :on-click #(dispatch [:remove-filter-line i])}
                        "×"]]
                      (when (seq filter-tag-ids)
                        [:div {:style {:display "flex" :flex-wrap "wrap" :gap "2px"
                                       :padding-left "4px"}}
                         (doall
                          (for [tid filter-tag-ids]
                            (when-let [tag (get tag-map tid)]
                              ^{:key tid}
                              [tag-chip tag nil])))])
                      (when tag-expanded?
                        [filter-tag-section i filter-obj tags expanded-tag-index])])))
                [:button.sb-add
                 {:on-click #(dispatch [:add-filter-line])}
                 "+ Legg til filter"]
                [:div {:style {:border-top "1px solid var(--border-soft)"
                               :padding-top "8px"
                               :display "flex" :justify-content "space-between"
                               :align-items "center"}}
                 [:button.btn-primary-xs
                  {:on-click #(dispatch [:store-category3])}
                  "Lagre"]
                 [:button.sb-edit
                  {:style {:color "var(--c-down)"}
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
      [:div {:style {:width "22px" :height "22px" :border-radius "5px"
                     :background-color c :cursor "pointer"
                     :border (if (= c selected-color)
                               "2px solid var(--text-bright)"
                               "2px solid transparent")
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
      [:td {:col-span 3 :style {:padding-top "8px"}}
       [:button.sb-add
        {:on-click #(dispatch [:edit-sidebar-category "new-id"])}
        (if is-editing? "Avbryt" "+ Ny kategori")]]]
     (when is-editing?
       [:tr {:key "new-cat-editor"}
        [:td {:col-span 3 :style {:padding 0}}
         [:div.sb-edit-panel
          [:input.sb-filter-input
           {:type "text" :placeholder "Navn"
            :value (:name builder-category)
            :on-change #(dispatch [:update-builder-category-name (-> % .-target .-value)])}]
          [color-palette
           (or (:color builder-category) "#5ce67e")
           #(dispatch [:update-builder-category-color %])
           used-colors]
          [:button.btn-primary-xs
           {:on-click #(dispatch [:store-category3])}
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
         [:div.sidebar-title "Tags" [:span.count (count tags)]]
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


(defn categories-sidebar [categories editing-txn editing-filter-index]
  (let [builder-category @(subscribe [:builder-category])
        cats (->> categories (filter :name))]
    [:div
     [:div.sidebar-title
      "Kategorier"
      [:span.count (count cats)]]
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
       [:td.multi-select-bar {:col-span 6 :style {:padding "10px 14px"}}
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

(defn transactions-sidebar
  "Right-hand categories+tags sidebar. Subscribes to the editing state and
   renders the existing categories-sidebar inside a .sidebar-panel wrapper."
  [categories]
  (let [transaction-row-editor @(subscribe [:transaction-row-editor])
        editing-txn (when-let [idx (:row-index transaction-row-editor)]
                      (some-> @(subscribe [:displayed-transactions-data])
                              :displayed-transactions
                              (get idx)))
        editing-filter-index (:editing-filter-index transaction-row-editor)]
    [categories-sidebar categories editing-txn editing-filter-index]))

;; --- Inline filter builder (programming-by-example) -------------------------
;; Rendered directly under the row being edited. One merged table of the
;; transactions the current filter matches (uncategorized + categorized). Mark a
;; row "Utelat" to exclude it and the pattern re-synthesizes to avoid it.

(defn- distinct-by [f coll]
  (second
   (reduce (fn [[seen acc] x]
             (let [k (f x)]
               (if (contains? seen k) [seen acc] [(conj seen k) (conj acc x)])))
           [#{} []]
           coll)))

(defn- fb-status [matched? polarity]
  (cond
    (= polarity :negative) [:span {:style {:color "var(--c-down)" :font-weight "600"}} "utelatt"]
    matched?               [:span {:style {:color "var(--c-up)" :font-weight "600"}} "matcher"]
    (= polarity :positive) [:span {:style {:color "var(--c-accent)" :font-weight "600"}} "valgt"]
    :else                  [:span {:style {:color "var(--text-faint)"}} "—"]))

(defn- fb-toggle-btn [label active? color on-click]
  [:button {:class "fb-btn"
            :style (if active?
                     {:background color :color "white" :border-color color}
                     {:color color})
            :on-click on-click}
   label])

(defn- fb-row [txn pattern category-map base-key]
  (let [tx-k (category/tx-key txn)
        polarity (:fb-polarity txn)
        negative? (= polarity :negative)
        positive? (= polarity :positive)
        amt (:amount txn)
        matched? (and (not negative?)
                      (some? (:description txn))
                      (not= pattern "")
                      (category/match-fun (:description txn) pattern))
        base? (= tx-k base-key)
        cat (get category-map (:category-id txn))
        cat-filter (when (and cat (:marked-by-filter? txn)) (category/find-sub-filter cat txn))]
    [:tr {:key (str tx-k) :class "txn-row" :style (when negative? {:opacity "0.5"})}
     [:td.txn-cat-cell
      [:span.cat-swatch {:title (:name cat)
                         :style {:background-color (or (:color cat) "var(--border-strong)")}}]]
     [:td {:class (str "txn-amount" (when (pos? amt) " pos"))}
      (gstring/format "%.2f" amt)]
     [:td.txn-date (date/unixtime->prettydate (:date txn))]
     [:td.txn-desc {:style (when negative? {:text-decoration "line-through"})}
      (highlight-text (:description txn) pattern)]
     [:td {:style {:color "var(--text-dim)"}} (or (:name cat) "—")]
     [:td {:style {:font-style "italic" :color "var(--text-dim)"}} (or cat-filter "")]
     [:td (fb-status matched? polarity)]
     [:td
      (if base?
        [:span {:style {:color "var(--text-dim)"}} "redigeres"]
        [:span {:style {:display "inline-flex" :gap "4px"}}
         (fb-toggle-btn "Match" positive? "var(--c-up)"
                        #(dispatch [:fb-set-example tx-k (if positive? :neutral :positive)]))
         (fb-toggle-btn "Utelat" negative? "var(--c-down)"
                        #(dispatch [:fb-set-example tx-k (if negative? :neutral :negative)]))])]]))

(def ^:private fb-row-limit 150)

(defn filter-builder-inline [transaction-row-editor base-transaction category-map]
  (let [all @(subscribe [:all-transactions])
        stats (:filter-statistics transaction-row-editor)
        positives (or (:fb-positives transaction-row-editor) #{})
        negatives (or (:fb-negatives transaction-row-editor) #{})
        pattern (or (:new-sub-filter transaction-row-editor) "")
        regex? (and (>= (count pattern) 6) (= (subs pattern 0 6) "regex:"))
        ;; Plain filters of length < 2 match almost everything; don't evaluate them.
        too-short? (and (not= pattern "") (not regex?) (< (count pattern) 2))
        base-key (category/tx-key base-transaction)
        matched (concat (:uncategorized-transactions stats) (:categorized-transactions stats))
        ;; Look up only the explicitly-chosen transactions (small sets) rather than
        ;; building a full key->txn map over every transaction on each render.
        chosen (into positives negatives)
        chosen-txns (when (seq chosen)
                      (filterv #(contains? chosen (category/tx-key %)) all))
        polarity-of (fn [k] (cond (contains? negatives k) :negative
                                  (contains? positives k) :positive
                                  :else :neutral))
        rows (->> (concat matched chosen-txns)
                  (distinct-by category/tx-key)
                  (map #(assoc % :fb-polarity (polarity-of (category/tx-key %))))
                  (sort-by (juxt #(if (nil? (:category-id %)) 0 1) #(- (:date %)))))
        row-count (count rows)
        unc-count (:uncategorized stats 0)
        cat-count (:categorized stats 0)
        same-count (:same-category stats 0)
        conflict-count (max 0 (- cat-count same-count))
        empty-pattern? (= pattern "")
        fb-msg (:fb-msg transaction-row-editor)
        matches? (fn [t] (and (some? (:description t))
                              (category/match-fun (:description t) pattern)))
        ;; The pattern is stale when it no longer agrees with the selection: a
        ;; negative still matches, or a positive no longer matches.
        dirty? (boolean
                (and (not empty-pattern?)
                     (or (some matches? (filter #(= :negative (:fb-polarity %)) rows))
                         (some #(not (matches? %)) (filter #(= :positive (:fb-polarity %)) rows)))))]
    [:div {:class "fb-panel"
           :style {:border-top "1px solid var(--border-soft)" :padding-top "8px"
                   :font-size "var(--fs-sm)"}}
     [:div {:style {:display "flex" :align-items "center" :gap "10px" :flex-wrap "wrap"
                    :margin-bottom "8px"}}
      [:span {:style {:font-size "var(--fs-xs)" :padding "2px 8px" :border-radius "999px"
                      :background "var(--bg-3)" :color "var(--text-dim)"}}
       (if regex? "regex" "tekst")]
      [:button {:class (if dirty? "btn-primary-xs" "fb-btn")
                :on-click #(dispatch [:fb-suggest])}
       "Foreslå filter"]
      [:span {:style {:color "var(--text-dim)"}}
       (str "Treff: " (count matched) " · ukategorisert " unc-count)
       (when (pos? conflict-count)
         [:span {:style {:color "var(--c-down)"}} (str " · " conflict-count " i andre kategorier")])]
      (when dirty?
        [:span {:style {:color "var(--c-accent)" :font-weight "600"}}
         "Utvalget er endret – klikk «Foreslå filter» for å oppdatere."])
      (when (and fb-msg (not dirty?))
        [:span {:style {:color "var(--c-warn)"}} fb-msg])]
     (cond
       empty-pattern?
       [:p.dim.small {:style {:margin 0}}
        "Skriv et filter over (eller bruk «Foreslå filter») for å se hvilke transaksjoner som matcher."]

       too-short?
       [:p.dim.small {:style {:margin 0}} "Filteret må være minst 2 tegn."]

       (empty? rows)
       [:p.dim.small {:style {:margin 0}} "Ingen transaksjoner matcher dette filteret."]

       :else
       [:div
        [:table.txn-table
         [:thead
          [:tr
           [:th {:style {:width "24px"}} ""]
           [:th {:style {:text-align "right"}} "Beløp"]
           [:th {:style {:text-align "right"}} "Dato"]
           [:th "Beskrivelse"]
           [:th "Kategori"]
           [:th "Filter"]
           [:th "Status"]
           [:th "Velg"]]]
         [:tbody
          (doall (for [txn (take fb-row-limit rows)]
                   (fb-row txn pattern category-map base-key)))]]
        (when (> row-count fb-row-limit)
          [:p.dim.small {:style {:margin "6px 0 0 0"}}
           (str "Viser " fb-row-limit " av " row-count " – avgrens filteret for å se færre.")])])]))

(defn transactions-table-main
  "Just the transactions table (no sidebar). Mirrors transactions-table's body."
  [displayed-transactions-data categories]
  (let [builder-category @(subscribe [:builder-category])
        transaction-row-editor @(subscribe [:transaction-row-editor])
        multi-select @(subscribe [:multi-select])
        multi-select-mode? (some? multi-select)
        all-transactions (:displayed-transactions displayed-transactions-data)
        sort-column (-> displayed-transactions-data :sort-column)
        sort-order (-> displayed-transactions-data :sort-order)
        category-map (into {} (map (juxt :id #(identity %)) categories))
        search @(subscribe [:transactions-search])
        search-q (when (and search (not= "" search)) (s/lower-case search))
        ;; Filter view-level by description or category name substring.
        transactions (if search-q
                       (filterv (fn [tx]
                                  (let [d (some-> (:description tx) s/lower-case)
                                        cat-name (some-> (get category-map (:category-id tx)) :name s/lower-case)]
                                    (or (and d (s/includes? d search-q))
                                        (and cat-name (s/includes? cat-name search-q)))))
                                all-transactions)
                       all-transactions)
        indexed-transactions (map-indexed vector transactions)
        rows (mapcat (fn [[index transaction]]
                       (transaction-row index transaction builder-category transaction-row-editor category-map multi-select))
                     indexed-transactions)]
    [:div {:style {:overflow-x "auto"}
           :on-mouse-down #(reset! selection-sum-state nil)
           :on-mouse-up (fn [_]
                          (js/setTimeout
                           (fn [] (reset! selection-sum-state (compute-selection-sum)))
                           10))}
     [:table.txn-table
      [:thead
       [:tr
        [:th {:style {:cursor "pointer" :font-size "11px"}
              :on-click #(dispatch [:toggle-multi-select-mode])}
         (if multi-select-mode? "Avbryt" "Velg")]
        [:th {:style {:width "12px" :min-width "12px" :padding 0}}]
        [:th {:style {:cursor "pointer" :text-align "right"} :on-click #(dispatch [:sort-column :amount])}
         "Beløp " (add-sort-sigil :amount sort-column sort-order)]
        [:th {:style {:cursor "pointer" :text-align "right"} :on-click #(dispatch [:sort-column :date])}
         "Dato " (add-sort-sigil :date sort-column sort-order)]
        [:th {:style {:cursor "pointer" :text-align "left"} :on-click #(dispatch [:sort-column :description])}
         "Beskrivelse " (add-sort-sigil :description sort-column sort-order)]
        [:th ""]]]
      [:tbody {:id "transactions-tbody"}
       (when multi-select-mode?
         (multi-select-editor multi-select (vec transactions) category-map categories))
       rows]]
     [selection-sum-popup @selection-sum-state]]))

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
