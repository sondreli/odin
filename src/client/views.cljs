(ns client.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [clojure.string :as s]
            [client.api :as api]
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
            [client.components.period-selector-v2.views :as period-sel-v2]
            [client.components.transactions-table-component.views :as t-table]
            [client.components.summed-table-component.views :as summed-table]
            [client.components.treemap-component.views :as treemap]
            [client.components.reports.views :as reports]
            [common.loan-service :as loan-svc])
  (:require-macros [reagent.core :refer [with-let]]))

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
                              [:a {:class "cursor-pointer" :on-click #(dispatch [:filter-path index])} level])))
        path (interpose " > " html-path)]
    [:div
     (for [elm path]
       elm)]))


(defn set-select-bg [color] (dispatch [:update-builder-category-color color]))

(defn- init-choices! [el choices-ref initial-color on-change used-colors]
  (when (and el (nil? @choices-ref))
    (let [used-set (or used-colors #{})
          template-fn (fn []
                        #js {:choice (fn [^js choices data]
                                       (let [class-name (.-itemChoice (.-classNames choices))
                                             data-id    (or (.-id data) "")
                                             data-value (or (.-value data) "")
                                             data-label (or (.-label data) "")
                                             used?      (contains? used-set data-value)
                                             div-el     (.createElement js/document "div")]
                                         (.setAttribute div-el "class" (s/join " " [(or class-name "choices__item choices__item--choice")]))
                                         (.setAttribute div-el "data-choice" "data-choice")
                                         (.setAttribute div-el "data-choice-selectable" "")
                                         (.setAttribute div-el "data-id" data-id)
                                         (.setAttribute div-el "data-value" data-value)
                                         (.setAttribute div-el "style"
                                                        (str "background-color: " data-value
                                                             "; width: 48px; height: 24px;"
                                                             " line-height: 24px; padding: 0 10px; margin: 0;"
                                                             " box-sizing: border-box;"
                                                             " display: flex; align-items: center; justify-content: center;"))
                                         (set! (.-innerText div-el) (if used? "\u2022" ""))
                                         div-el))

                             :item   (fn [^js choices data]
                                       (let [class-name (.-item (.-classNames choices))
                                             data-value (or (.-value data) "")
                                             data-label (or (.-label data) "")
                                             div-el     (.createElement js/document "div")]
                                         (.setAttribute div-el "class" (s/join " " [(or class-name "choices__item choices__item--selected")]))
                                         (.setAttribute div-el "data-item" "")
                                         (.setAttribute div-el "data-id" (or (.-id data) ""))
                                         (.setAttribute div-el "data-value" data-value)
                                         (.setAttribute div-el "style"
                                                        (str "background-color: " data-value
                                                             "; width: 48px; height: 24px;"
                                                             " padding: 0 10px; margin: 0; box-sizing: border-box;"
                                                             " display: flex; align-items: center; justify-content: center;"))
                                         (set! (.-innerText div-el) "")
                                         div-el))})

          choices-config #js {:itemSelectText ""
                              :shouldSort false
                              :allowHTML false
                              :searchEnabled false
                              :callbackOnCreateTemplates template-fn}

          instance (js/Choices. el choices-config)]

      (reset! choices-ref instance)
      (g/set el "choicesInstance" instance)

      ;; Set initial value AFTER initialization
      (when (and initial-color (not= initial-color ""))
        (.setChoiceByValue instance initial-color))

      ;; Optional: trigger your background update on init
      (when (and initial-color on-change)
        (on-change initial-color)))))

(defn color-selector [{:keys [initial-color on-change used-colors]}]
  (with-let [choices-ref (r/atom nil)]
    (let [colors (map #(-> [% 0.6 0.9]
                           color/hsv2rgb
                           color/color-base10->base16
                           color/color-str)
                      (color/generate-hues 16))]
      [:div#color-selector-outer
       [:select.color-select
        {:ref #(when %
                 (init-choices! % choices-ref initial-color on-change used-colors))
         :on-change (fn [e]
                      (let [v (.. e -target -value)]
                        (when on-change
                          (on-change v))
                        (set-select-bg v)))}
        (for [[i color] (map-indexed vector colors)]
          [:option {:key i :value color} ""])]]) ; empty label → color swatch only

    (finally
      (when-let [i @choices-ref]
        (.destroy i)))))

(defn add-disabled [props expr?]
  (if expr?
    props
    (assoc props :disabled "disabled")))

(defn get-value-of-parent-row [elm]
  (-> elm  .-target (. closest ".row") .-attributes .-value .-value))


(defn- parse-target-num [t]
  (when t
    (let [n (if (number? t) t (js/parseFloat (str t)))]
      (when (and (number? n) (not (js/isNaN n)) (pos? n)) n))))

(defn- diff-bar-cell
  "Renders a horizontal bar in a td. zero-offset is the percentage position
   of the zero line. diff is target - spent."
  [diff max-abs]
  (when (and max-abs (pos? max-abs))
    (let [bar-pct (* 50 (/ (Math/abs diff) max-abs))
          positive? (>= diff 0)
          bar-color (if positive? "#22c55e" "#ef4444")]
      [:td {:style {:padding "0 8px" :width "120px" :min-width "120px"}}
       [:div {:style {:position "relative" :height "16px" :width "100%"}}
        [:div {:style {:position "absolute"
                       :left "50%" :top "0" :bottom "0"
                       :width "1px"
                       :background-color "#ccc"}}]
        [:div {:style {:position "absolute"
                       :height "12px"
                       :top "2px"
                       :border-radius "2px"
                       :background-color bar-color
                       :left (if positive? "50%" (str (- 50 bar-pct) "%"))
                       :width (str bar-pct "%")}}]]])))

(def ^:private hidden-ids #{"in" "out"})

(defn- target-input [category]
  [:input {:type "text"
           :value (or (:target category) "")
           :style {:width "70px" :text-align "right" :padding "2px 4px"
                   :border "1px solid #ddd" :border-radius "3px"
                   :font-size "0.9em" :color "#555"}
           :on-change #(dispatch [:update-category-target (:id category) (-> % .-target .-value)])
           :on-blur #(dispatch [:save-category-target (:id category)])}])

(def ^:private bucket-order ["needs" "wants" "should" nil])
(def ^:private bucket-labels {"needs" "Behov" "wants" "Ønsker" "should" "Bør" nil "Ukategorisert"})

(defn- expense-row [index category max-abs-diff]
  (let [spent (Math/abs (or (:amount category) 0))
        tgt   (or (parse-target-num (:target category)) 0)
        diff  (- tgt spent)]
    [[:tr {:value (:id category) :key (:name category) :class "row"}
      [:td [:a {:class "cursor-pointer" :on-click #(dispatch [:edit-category3 (get-value-of-parent-row %) index])}
            "Endre"]]
      [:td {:bgcolor (:color category)}
       [:a {:class "cursor-pointer" :on-click #(dispatch [:view-category (:name category)])}
        (:name category)]]
      [:td {:align "right"} [target-input category]]
      [:td {:align "right" :style {:padding-right "1em"}}
       (gstring/format "%.0f" (or (:amount category) 0))]
      [:td {:align "right" :style {:padding-right "1em"
                                   :color (if (>= diff 0) "#22c55e" "#ef4444")
                                   :font-weight "500"}}
       (gstring/format "%.0f" diff)]
      [diff-bar-cell diff max-abs-diff]]]))

(defn- income-row [index category]
  [[:tr {:value (:id category) :key (:name category) :class "row"}
    [:td [:a {:class "cursor-pointer" :on-click #(dispatch [:edit-category3 (get-value-of-parent-row %) index])}
          "Endre"]]
    [:td {:bgcolor (:color category)}
     [:a {:class "cursor-pointer" :on-click #(dispatch [:view-category (:name category)])}
      (:name category)]]
    [:td]
    [:td {:align "right" :style {:padding-right "1em"}}
     (gstring/format "%.0f" (or (:amount category) 0))]
    [:td] [:td]]])

(defn- edit-row [index category builder-category ready-to-store? show-diff? max-abs-diff used-colors]
  (let [spent (Math/abs (or (:amount category) 0))
        tgt   (or (parse-target-num (:target category)) 0)
        diff  (- tgt spent)]
    [[:tr {:value (:id category) :key "edit-category-row" :class "row"}
      [:td [:a {:class "cursor-pointer" :on-click #(dispatch [:edit-category3 (get-value-of-parent-row %) index])}
            "Lukk"]]
      [:td {:bgcolor (:color category)}
       [:div {:style {:display "flex" :align-items "center"}}
        [:input {:type "text" :placeholder "Navn" :value (:name builder-category)
                 :on-change #(dispatch [:update-builder-category-name (-> % .-target .-value)])}]
        [color-selector {:initial-color (:color-value category)
                         :on-change     (fn [new-color] (set-select-bg new-color))
                         :used-colors   used-colors
                         :key           (:id category)}]]]
      [:td {:align "right"} (when show-diff? [target-input category])]
      [:td {:align "right" :style {:padding-right "1em"}}
       (gstring/format "%.0f" (if show-diff? (- spent) (or (:amount category) 0)))]
      (if show-diff?
        [:<>
         [:td {:align "right" :style {:padding-right "1em"
                                      :color (if (>= diff 0) "#22c55e" "#ef4444")
                                      :font-weight "500"}}
          (gstring/format "%.0f" diff)]
         [diff-bar-cell diff max-abs-diff]]
        [:<> [:td] [:td]])]
     [:tr {:key (str (:id category) "2")}
      [:td {:style {:background-color "#f8f9fa"
                    :vertical-align "top"
                    :padding "6px"}}
       [:div {:style {:display "flex" :flex-direction "column" :gap "4px"}}
        [:button (-> {:class "button-class"
                      :on-click #(dispatch [:store-category3])}
                     (add-disabled ready-to-store?)) "Lagre"]
        [:select {:value (or (:bucket builder-category) "")
                  :on-change #(dispatch [:update-builder-category-bucket (-> % .-target .-value)])
                  :style {:padding "2px" :font-size "12px" :border "1px solid #ccc"
                          :border-radius "3px" :margin-top "4px"}}
         [:option {:value ""} "—"]
         [:option {:value "needs"} "Behov"]
         [:option {:value "wants"} "Ønsker"]
         [:option {:value "should"} "Bør"]]
        [:a {:class "cursor-pointer"
             :style {:color "#c00" :margin-top "auto" :padding-top "12px"}
             :on-click #(dispatch [:delete-category (get-value-of-parent-row %)])}
         "Slett"]]]
      [:td {:col-span 5 :style {:background-color "#f8f9fa"
                                :padding "6px"}}
       [:textarea {:type "text"
                   :rows 5
                   :value (-> builder-category :marker :value)
                   :on-change #(dispatch [:mark-transactions (-> % .-target .-value)])
                   :style {:width "100%"
                           :resize "vertical"
                           :border "1px solid #ccc"
                           :padding "6px"
                           :box-sizing "border-box"}}]]]]))

(defn- sum-row [label amounts targets diff-value {:keys [bold? border-top?]}]
  (let [diff-color (cond (pos? diff-value) "#22c55e"
                         (neg? diff-value) "#ef4444"
                         :else "#555")]
    [:tr {:key label
          :style (merge (when bold? {:font-weight "700"})
                        (when border-top? {:border-top "2px solid #333"}))}
     [:td]
     [:td {:style {:padding-left "4px"}} label]
     [:td {:align "right"} (when targets (gstring/format "%.0f" targets))]
     [:td {:align "right" :style {:padding-right "1em"}} (gstring/format "%.0f" amounts)]
     [:td {:align "right" :style {:padding-right "1em" :color diff-color :font-weight "500"}}
      (when diff-value (gstring/format "%.0f" diff-value))]
     [:td]]))

(defn- bucket-section-header [key label pct]
  [:tr {:key key}
   [:td {:col-span 6
         :style {:padding "6px 4px 2px" :font-weight "600"
                 :font-size "0.9em" :color "#555"
                 :border-bottom "1px solid #ddd"}}
    label
    (when pct
      [:span {:style {:font-weight "400" :color "#888" :margin-left "8px"}}
       (str (gstring/format "%.0f" pct) "%")])]])

(defn- bucket-sum-row [label cats]
  (let [amount (->> cats (map #(Math/abs (or (:amount %) 0))) (reduce + 0))
        target (->> cats (map #(or (parse-target-num (:target %)) 0)) (reduce + 0))
        diff   (- target amount)]
    [sum-row (str "Sum " label) (- amount) target diff {:border-top? true}]))

(defn categories []
  (let [all-categories (->> @(subscribe [:summed-categories])
                            (filter :name)
                            (remove #(hidden-ids (:id %))))
        income-cats  (->> all-categories
                          (filter #(or (pos? (:amount %))
                                       (= "ukategorisert-in" (:id %)))))
        expense-cats (->> all-categories
                          (remove #(or (pos? (:amount %))
                                       (= "ukategorisert-in" (:id %))
                                       (= "ukategorisert-out" (:id %)))))
        ukategorisert-out (first (filter #(= "ukategorisert-out" (:id %)) all-categories))
        expense-cats (if ukategorisert-out
                       (conj (vec expense-cats) ukategorisert-out)
                       expense-cats)
        max-abs-diff (->> expense-cats
                          (map (fn [c]
                                 (let [spent (Math/abs (or (:amount c) 0))
                                       tgt   (or (parse-target-num (:target c)) 0)]
                                   (Math/abs (- tgt spent)))))
                          (reduce max 0))
        used-colors (into #{} (keep :color all-categories))
        builder-category @(subscribe [:builder-category])
        edit-category? (fn [cat] (= (:id cat) (:id builder-category)))
        new-category? (= (:id builder-category) "new-id")
        ready-to-store? (category/ready-to-store? builder-category)

        income-rows (mapcat identity
                     (map-indexed
                      (fn [idx cat]
                        (if (edit-category? cat)
                          (edit-row idx cat builder-category ready-to-store? false max-abs-diff used-colors)
                          (income-row idx cat)))
                      income-cats))

        buckets (group-by #(or (:bucket %) nil) expense-cats)

        expense-sum-amount (->> expense-cats (map #(Math/abs (or (:amount %) 0))) (reduce + 0))
        expense-sum-target (->> expense-cats (map #(or (parse-target-num (:target %)) 0)) (reduce + 0))
        expense-diff       (- expense-sum-target expense-sum-amount)

        bucket-rows
        (mapcat
         (fn [bucket-key]
           (let [cats (get buckets bucket-key)
                 label (get bucket-labels bucket-key)
                 bucket-spend (->> cats (map #(Math/abs (or (:amount %) 0))) (reduce + 0))
                 pct (when (pos? expense-sum-target)
                       (* 100.0 (/ bucket-spend expense-sum-target)))]
             (when (seq cats)
               (concat
                [(bucket-section-header (str "bh-" bucket-key) label pct)]
                (mapcat identity
                 (map-indexed
                  (fn [idx cat]
                    (if (edit-category? cat)
                      (edit-row idx cat builder-category ready-to-store? true max-abs-diff used-colors)
                      (expense-row idx cat max-abs-diff)))
                  cats))
                [(bucket-sum-row label cats)]))))
         bucket-order)

        new-row (if new-category?
                  (edit-row 0 {:id "new-id"} builder-category ready-to-store? true max-abs-diff used-colors)
                  [[:tr {:key "newrow"}
                    [:td [:a {:class "cursor-pointer" :on-click #(dispatch [:edit-category3 "new-id" 0])} "Ny"]]]])

        income-sum-amount  (->> income-cats (map #(or (:amount %) 0)) (reduce + 0))

        total-amount       (- income-sum-amount expense-sum-amount)
        total-diff         (- expense-sum-target expense-sum-amount)]
    [:div
     [:h3 "Budsjett"]
     [:table
      [:thead
       [:tr
        [:th]
        [:th {:style {:text-align "left"}} "Kategori"]
        [:th {:style {:text-align "right"}} "Planlagt"]
        [:th {:style {:text-align "right" :padding-right "1em"}} "Brukt"]
        [:th {:style {:text-align "right" :padding-right "1em"}} "Differanse"]
        [:th]]]
      [:tbody {:id "categories-tbody"}
       income-rows
       [:tr {:key "sep-income"} [:td {:col-span 6 :style {:padding "4px 0"}}]]
       bucket-rows
       (mapcat identity [new-row])
       [:tr {:key "sep-sums"} [:td {:col-span 6 :style {:padding "4px 0"}}]]
       [sum-row "Sum utgifter" (- expense-sum-amount) expense-sum-target expense-diff
        {:border-top? true}]
       [sum-row "Sum inntekter" income-sum-amount nil nil {}]
       [sum-row "Resultat" total-amount expense-sum-target total-diff
        {:bold? true :border-top? true}]]]]))

(defn- loading-banner []
  (let [loading @(subscribe [:loading])]
    (when (= loading "true")
      [:div {:style {:display "flex" :align-items "center" :gap "10px"
                     :padding "12px 16px" :margin-bottom "12px"
                     :background-color "#f0f7ff" :border "1px solid #c5ddf5"
                     :border-radius "6px" :color "#1a5276"}}
       [:span {:style {:display "inline-block" :animation "spin 1s linear infinite"}} "⏳"]
       [:span {:style {:font-size "14px"}}
        "Henter transaksjoner fra banken... Dette kan ta opptil et minutt."]])))

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
       [:li (menu-item (:name category) :as-filter transaction-desc)])]]
   ])

(defn displayed-transactions-view-selector []
  (let [display-option (-> @(subscribe [:displayed-transactions-data]) :display-option)
        cell-style (fn [selected? last?]
                     (cond-> {:padding "4px 12px"
                              :font-size "13px"
                              :background-color (if selected? "#4a6cf7" "#e8e8e8")
                              :color (if selected? "#fff" "inherit")
                              :cursor "pointer"
                              :text-align "center"
                              :min-width "60px"
                              :user-select "none"
                              :transition "background-color 0.1s ease"}
                       (not last?) (assoc :border-right "1px solid #ccc")))
        options [[:table       "Tabell"]
                 [:bar-chart   "Stolpediagram"]
                 [:summed-table "Summert"]]]
    [:div {:style {:display "inline-flex"
                   :border "1px solid #ccc"
                   :border-radius "4px"
                   :overflow "hidden"}}
     (for [[i [opt-key label]] (map-indexed vector options)
           :let [selected? (= display-option opt-key)
                 last? (= i (dec (count options)))]]
       ^{:key opt-key}
       [:div {:on-click #(dispatch [:navigate [nil opt-key nil]])
              :style (cell-style selected? last?)}
        label])]))

(defn remove-barchart []
  (-> d3 (.selectAll "#mychart svg") (.remove)))

(defn displayed-transactions-viewer []
  (remove-barchart)
  (let [displayed-transactions-data @(subscribe [:displayed-transactions-data])
        cats @(subscribe [:categories])
        period @(subscribe [:period])
        display-option (:display-option displayed-transactions-data)
        displayed-transactions (:displayed-transactions displayed-transactions-data)
        chart-size (:chart-size displayed-transactions-data)]
    (println "display-option:" (pr-str display-option))
    (case display-option
      :table (t-table/transactions-table displayed-transactions-data cats)
      :bar-chart (chart/stacked-barchart displayed-transactions cats period chart-size)
      :summed-table [summed-table/summed-table displayed-transactions-data cats]
      [:div [:p (str "Unknown display option: " (pr-str display-option))]])
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



(def ^:private menu-items
  [[:transaksjoner "Transaksjoner"]
   [:budsjett "Budsjett"]
   [:laan "Lån"]
   [:kontoer "Kontoer"]
   [:rapporter "Rapporter"]])

(defn- nav-sidebar []
  (let [active @(subscribe [:active-menu])]
    [:nav {:style {:width "180px" :min-width "180px"
                   :min-height "100vh"
                   :border-right "1px solid #ddd"
                   :padding "16px 0"
                   :background-color "#fafafa"}}
     [:ul {:style {:list-style "none" :margin 0 :padding 0}}
      (doall
       (for [[k label] menu-items]
         [:li {:key k}
          [:a {:class "cursor-pointer"
               :style (merge {:display "block" :padding "10px 20px"
                              :font-size "14px" :color "#333"
                              :text-decoration "none"
                              :border-left "3px solid transparent"}
                             (when (= k active)
                               {:font-weight "600"
                                :background-color "#f8f9fa"
                                :border-left-color "#333"}))
               :on-click #(dispatch [:set-active-menu k])}
           label]]))]]))

(defn- transaksjoner-content []
  [:div
   [loading-banner]
   [treemap/category-treemap {:height-ratio 0.25}]
   [:div {:style {:margin-top "16px" :display "flex" :flex-wrap "nowrap"
                  :gap "12px" :align-items "flex-start"}}
    [displayed-transactions-view-selector]
    [period-sel-v2/period-selector]]
   (filter-path)
   (displayed-transactions-viewer)])

(defn- budsjett-content []
  [:div
   [period-sel-v2/period-selector]
   (categories)])

(defn- placeholder-content [title]
  [:div {:style {:padding "40px" :color "#888"}}
   [:h3 title]
   [:p "Kommer snart"]])

;; Auth views

(defn- login-form []
  (let [email (r/atom "")
        password (r/atom "")
        auth @(subscribe [:auth])]
    (fn []
      [:div {:style {:max-width "400px" :margin "100px auto" :padding "40px"
                      :border "1px solid #ddd" :border-radius "8px"
                      :background-color "#fafafa"}}
       [:h2 {:style {:margin-bottom "24px" :text-align "center"}} "Logg inn"]
       (when (:error auth)
         [:div {:style {:padding "8px 12px" :margin-bottom "16px"
                        :background-color "#fee" :border "1px solid #fcc"
                        :border-radius "4px" :color "#c33" :font-size "14px"}}
          (:error auth)])
       [:div {:style {:margin-bottom "16px"}}
        [:label {:style {:display "block" :margin-bottom "4px" :font-size "14px" :color "#555"}} "E-post"]
        [:input {:type "email" :value @email
                 :style {:width "100%" :padding "8px 12px" :border "1px solid #ccc"
                         :border-radius "4px" :font-size "14px" :box-sizing "border-box"}
                 :on-change #(reset! email (-> % .-target .-value))}]]
       [:div {:style {:margin-bottom "24px"}}
        [:label {:style {:display "block" :margin-bottom "4px" :font-size "14px" :color "#555"}} "Passord"]
        [:input {:type "password" :value @password
                 :style {:width "100%" :padding "8px 12px" :border "1px solid #ccc"
                         :border-radius "4px" :font-size "14px" :box-sizing "border-box"}
                 :on-change #(reset! password (-> % .-target .-value))
                 :on-key-down #(when (= (.-key %) "Enter")
                                 (dispatch [:login @email @password]))}]]
       [:button {:on-click #(dispatch [:login @email @password])
                 :disabled (:loading? auth)
                 :style {:width "100%" :padding "10px" :background-color "#333"
                         :color "#fff" :border "none" :border-radius "4px"
                         :font-size "14px" :cursor "pointer"}}
        (if (:loading? auth) "Logger inn..." "Logg inn")]
       [:p {:style {:text-align "center" :margin-top "16px" :font-size "14px" :color "#666"}}
        "Har du ikke konto? "
        [:a {:class "cursor-pointer" :style {:color "#333" :text-decoration "underline"}
             :on-click #(dispatch [:set-auth-view :register])}
         "Registrer deg"]]])))

(defn- register-form []
  (let [email (r/atom "")
        password (r/atom "")
        confirm-password (r/atom "")
        local-error (r/atom nil)
        auth @(subscribe [:auth])]
    (fn []
      [:div {:style {:max-width "400px" :margin "100px auto" :padding "40px"
                      :border "1px solid #ddd" :border-radius "8px"
                      :background-color "#fafafa"}}
       [:h2 {:style {:margin-bottom "24px" :text-align "center"}} "Registrer deg"]
       (when (or (:error auth) @local-error)
         [:div {:style {:padding "8px 12px" :margin-bottom "16px"
                        :background-color "#fee" :border "1px solid #fcc"
                        :border-radius "4px" :color "#c33" :font-size "14px"}}
          (or @local-error (:error auth))])
       [:div {:style {:margin-bottom "16px"}}
        [:label {:style {:display "block" :margin-bottom "4px" :font-size "14px" :color "#555"}} "E-post"]
        [:input {:type "email" :value @email
                 :style {:width "100%" :padding "8px 12px" :border "1px solid #ccc"
                         :border-radius "4px" :font-size "14px" :box-sizing "border-box"}
                 :on-change #(reset! email (-> % .-target .-value))}]]
       [:div {:style {:margin-bottom "16px"}}
        [:label {:style {:display "block" :margin-bottom "4px" :font-size "14px" :color "#555"}} "Passord"]
        [:input {:type "password" :value @password
                 :style {:width "100%" :padding "8px 12px" :border "1px solid #ccc"
                         :border-radius "4px" :font-size "14px" :box-sizing "border-box"}
                 :on-change #(reset! password (-> % .-target .-value))}]]
       [:div {:style {:margin-bottom "24px"}}
        [:label {:style {:display "block" :margin-bottom "4px" :font-size "14px" :color "#555"}} "Bekreft passord"]
        [:input {:type "password" :value @confirm-password
                 :style {:width "100%" :padding "8px 12px" :border "1px solid #ccc"
                         :border-radius "4px" :font-size "14px" :box-sizing "border-box"}
                 :on-change #(reset! confirm-password (-> % .-target .-value))
                 :on-key-down #(when (= (.-key %) "Enter")
                                 (if (not= @password @confirm-password)
                                   (reset! local-error "Passordene er ikke like")
                                   (do (reset! local-error nil)
                                       (dispatch [:register @email @password]))))}]]
       [:button {:on-click #(if (not= @password @confirm-password)
                              (reset! local-error "Passordene er ikke like")
                              (do (reset! local-error nil)
                                  (dispatch [:register @email @password])))
                 :disabled (:loading? auth)
                 :style {:width "100%" :padding "10px" :background-color "#333"
                         :color "#fff" :border "none" :border-radius "4px"
                         :font-size "14px" :cursor "pointer"}}
        (if (:loading? auth) "Registrerer..." "Registrer")]
       [:p {:style {:text-align "center" :margin-top "16px" :font-size "14px" :color "#666"}}
        "Har du allerede konto? "
        [:a {:class "cursor-pointer" :style {:color "#333" :text-decoration "underline"}
             :on-click #(dispatch [:set-auth-view :login])}
         "Logg inn"]]])))

(defn- auth-view []
  (let [view @(subscribe [:auth-view])]
    (case view
      :register [register-form]
      [login-form])))

;; Accounts page

(defn- provider-label [provider-key]
  (case provider-key
    "sparebank1-ost" "Sparebank1 Østlandet"
    provider-key))

(defn- build-callback-uri []
  (let [base api/*base-url*]
    (if (s/starts-with? base "http")
      ;; Absolute URL (local dev): e.g. http://localhost:8080 -> http://localhost:8080/auth/bank/callback
      (str base "/auth/bank/callback")
      ;; Relative URL (production): e.g. /api -> https://domain/api/auth/bank/callback
      (str (.-origin js/window.location) base "/auth/bank/callback"))))

(defn- copy-to-clipboard [text]
  (.writeText js/navigator.clipboard text))

(defn- kontoer-content []
  (let [callback-uri (build-callback-uri)
        client-id (r/atom "")
        client-secret (r/atom "")
        show-form? (r/atom false)
        copied? (r/atom false)
        confirm-delete (r/atom nil)]
    (fn []
      (let [accounts @(subscribe [:accounts])]
        [:div {:style {:padding "20px 0"}}
         [:h3 "Kontoer"]
         (when @confirm-delete
           [:div {:style {:position "fixed" :inset "0" :z-index 9999
                          :background-color "rgba(0,0,0,0.5)"
                          :display "flex" :align-items "center" :justify-content "center"}}
            [:div {:style {:background-color "#fff" :padding "24px 32px"
                           :border-radius "8px" :text-align "center"
                           :box-shadow "0 4px 20px rgba(0,0,0,0.3)"
                           :max-width "400px"}}
             [:p {:style {:margin "0 0 16px" :font-size "16px"}}
              "Er du sikker på at du vil fjerne denne kontoen?"]
             [:div {:style {:display "flex" :gap "8px" :justify-content "center"}}
              [:button {:on-click #(do (dispatch [:delete-account @confirm-delete])
                                      (reset! confirm-delete nil))
                        :style {:padding "8px 20px" :background-color "#c00" :color "#fff"
                                :border "none" :border-radius "4px" :cursor "pointer"
                                :font-size "14px"}}
               "Fjern"]
              [:button {:on-click #(reset! confirm-delete nil)
                        :style {:padding "8px 20px" :background-color "#fff" :color "#333"
                                :border "1px solid #ccc" :border-radius "4px"
                                :cursor "pointer" :font-size "14px"}}
               "Avbryt"]]]])
         (if (empty? accounts)
         [:p {:style {:color "#888" :margin "20px 0"}} "Ingen kontoer koblet til ennå."]
         [:table {:style {:width "100%" :border-collapse "collapse" :margin-bottom "24px"}}
          [:thead
           [:tr
            [:th {:style {:text-align "left" :padding "8px" :border-bottom "2px solid #ddd"}} "Konto"]
            [:th {:style {:text-align "left" :padding "8px" :border-bottom "2px solid #ddd"}} "Bank"]
            [:th {:style {:text-align "left" :padding "8px" :border-bottom "2px solid #ddd"}} ""]]]
          [:tbody
           (for [account accounts]
             [:tr {:key (:account-id account)}
              [:td {:style {:padding "8px" :border-bottom "1px solid #eee"}}
               (or (:account-name account) "—")]
              [:td {:style {:padding "8px" :border-bottom "1px solid #eee"}}
               (provider-label (:provider account))]
              [:td {:style {:padding "8px" :border-bottom "1px solid #eee"}}
               [:a {:class "cursor-pointer"
                    :style {:color "#c00" :font-size "13px"}
                    :on-click #(reset! confirm-delete (:account-id account))}
                "Fjern"]]])]])
       [:div {:style {:margin-top "16px"}}
        [:h4 {:style {:margin-bottom "8px"}} "Koble til ny konto"]
        (if-not @show-form?
          [:button {:on-click #(reset! show-form? true)
                    :style {:padding "8px 16px" :background-color "#333" :color "#fff"
                            :border "none" :border-radius "4px" :cursor "pointer"
                            :font-size "14px"}}
           "Koble til Sparebank1 Østlandet"]
          [:div {:style {:max-width "520px" :margin-top "12px"
                         :padding "20px" :border "1px solid #ddd" :border-radius "8px"
                         :background-color "#fafafa"}}
           [:p {:style {:margin "0 0 8px" :font-size "14px" :line-height "1.5"}}
            "For å koble til en bankkonto trenger du en utviklerklient fra Sparebank1."]
           [:ol {:style {:margin "0 0 16px" :padding-left "20px" :font-size "14px" :line-height "1.8"}}
            [:li "Gå til "
             [:a {:href "https://developer.sparebank1.no"
                  :target "_blank"
                  :style {:color "#0066cc"}}
              "developer.sparebank1.no"]
             " og klikk \"Log in\" for å opprette en utviklerkonto."]
            [:li "Følg \"Getting started\"-guiden og opprett en ny klient."]
            [:li "Gi klienten et valgfritt navn og lim inn callback-URLen nedenfor."]
            [:li "Kopier klientens Client ID og Client Secret og lim inn her."]]
           [:div {:style {:margin-bottom "16px"}}
            [:label {:style {:font-size "13px" :font-weight "600" :color "#555"}} "Callback URI"]
            [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-top "4px"}}
             [:code {:style {:flex "1" :padding "8px" :background-color "#eee"
                             :border-radius "4px" :font-size "12px"
                             :word-break "break-all"}}
              callback-uri]
             [:button {:on-click #(do (copy-to-clipboard callback-uri)
                                     (reset! copied? true)
                                     (js/setTimeout (fn [] (reset! copied? false)) 2000))
                       :style {:padding "6px 12px" :background-color "#555" :color "#fff"
                               :border "none" :border-radius "4px" :cursor "pointer"
                               :font-size "12px" :white-space "nowrap"}}
              (if @copied? "Kopiert!" "Kopier")]]]
           [:div {:style {:margin-bottom "12px"}}
            [:label {:style {:font-size "13px" :font-weight "600" :color "#555"}} "Client ID"]
            [:input {:type "text"
                     :value @client-id
                     :on-change #(reset! client-id (.. % -target -value))
                     :placeholder "f.eks. 516d21d1-39f1-4712-978c-..."
                     :style {:width "100%" :padding "8px" :margin-top "4px"
                             :border "1px solid #ccc" :border-radius "4px"
                             :font-size "14px" :box-sizing "border-box"}}]]
           [:div {:style {:margin-bottom "16px"}}
            [:label {:style {:font-size "13px" :font-weight "600" :color "#555"}} "Client Secret"]
            [:input {:type "password"
                     :value @client-secret
                     :on-change #(reset! client-secret (.. % -target -value))
                     :placeholder "f.eks. c6306ed8-08c9-4de3-..."
                     :style {:width "100%" :padding "8px" :margin-top "4px"
                             :border "1px solid #ccc" :border-radius "4px"
                             :font-size "14px" :box-sizing "border-box"}}]]
           [:div {:style {:display "flex" :gap "8px"}}
            [:button {:on-click #(when (and (seq @client-id) (seq @client-secret))
                                   (dispatch [:connect-account "sparebank1-ost" nil
                                              @client-id @client-secret callback-uri]))
                      :disabled (or (empty? @client-id) (empty? @client-secret))
                      :style {:padding "8px 16px"
                              :background-color (if (or (empty? @client-id) (empty? @client-secret))
                                                  "#999" "#333")
                              :color "#fff" :border "none" :border-radius "4px"
                              :cursor (if (or (empty? @client-id) (empty? @client-secret))
                                        "not-allowed" "pointer")
                              :font-size "14px"}}
             "Koble til"]
            [:button {:on-click #(do (reset! show-form? false)
                                     (reset! client-id "")
                                     (reset! client-secret ""))
                      :style {:padding "8px 16px" :background-color "#fff" :color "#333"
                              :border "1px solid #ccc" :border-radius "4px"
                              :cursor "pointer" :font-size "14px"}}
             "Avbryt"]]])]]))))

(defn- deleting-overlay []
  [:div {:style {:position "fixed" :inset "0" :z-index 9999
                 :background-color "rgba(0,0,0,0.5)"
                 :display "flex" :align-items "center" :justify-content "center"}}
   [:div {:style {:background-color "#fff" :padding "32px 48px"
                  :border-radius "8px" :text-align "center"
                  :box-shadow "0 4px 20px rgba(0,0,0,0.3)"}}
    [:div {:style {:font-size "24px" :margin-bottom "12px" :animation "spin 1s linear infinite"}}
     [:span {:style {:display "inline-block"
                     :animation "spin 1s linear infinite"}} "⏳"]]
    [:p {:style {:margin 0 :font-size "16px" :color "#333" :font-weight "500"}}
     "Sletter konto og all data..."]
    [:p {:style {:margin "8px 0 0" :font-size "13px" :color "#888"}}
     "Dette kan ta noen sekunder."]]])

(defn- user-menu []
  (let [open? (r/atom false)
        confirm-delete? (r/atom false)
        deleting? (r/atom false)]
    (fn []
      (let [auth @(subscribe [:auth])
            user (:user auth)]
        [:<>
         (when @deleting?
           [deleting-overlay])
         (when user
           [:div {:style {:position "relative" :display "inline-block"}
                  :on-mouse-leave #(when-not @deleting?
                                     (reset! open? false)
                                     (reset! confirm-delete? false))}
            [:a {:class "cursor-pointer"
                 :style {:display "flex" :align-items "center" :gap "4px"
                         :color "#333" :font-size "13px"}
                 :on-click #(swap! open? not)}
             [:span (:email user)]
             [:span {:style {:font-size "10px"}} (if @open? "▲" "▼")]]
            (when @open?
              [:div {:style {:position "absolute" :right 0 :top "100%"
                             :padding-top "4px" :min-width "180px" :z-index 100}}
               [:div {:style {:background-color "#fff" :border "1px solid #ddd"
                              :border-radius "6px" :box-shadow "0 2px 8px rgba(0,0,0,0.12)"
                              :overflow "hidden"}}
                [:a {:class "cursor-pointer"
                     :style {:display "block" :padding "10px 16px"
                             :font-size "13px" :color "#333"
                             :border-bottom "1px solid #eee"}
                     :on-click #(do (reset! open? false)
                                    (dispatch [:logout]))}
                 "Logg ut"]
                (if @confirm-delete?
                  [:div {:style {:padding "10px 16px" :background-color "#fff5f5"}}
                   [:p {:style {:font-size "12px" :color "#c33" :margin "0 0 8px 0"}}
                    "Er du sikker? Alt av data vil bli slettet permanent."]
                   [:div {:style {:display "flex" :gap "8px"}}
                    [:button {:on-click #(do (reset! deleting? true)
                                             (reset! open? false)
                                             (dispatch [:delete-user]))
                              :style {:padding "4px 12px" :background-color "#c33" :color "#fff"
                                      :border "none" :border-radius "4px" :font-size "12px"
                                      :cursor "pointer"}}
                     "Slett"]
                    [:button {:on-click #(reset! confirm-delete? false)
                              :style {:padding "4px 12px" :background-color "#eee" :color "#333"
                                      :border "none" :border-radius "4px" :font-size "12px"
                                      :cursor "pointer"}}
                     "Avbryt"]]]
                  [:a {:class "cursor-pointer"
                       :style {:display "block" :padding "10px 16px"
                               :font-size "13px" :color "#c33"}
                       :on-click #(reset! confirm-delete? true)}
                   "Slett konto"])]])])]))))

;; ---------- Loans ----------

(def ^:private bar-colors
  {:paid-principal "#16a34a"
   :paid-interest  "#a3e635"
   :paid-fees      "#d4d404"
   :rem-principal  "#2563eb"
   :rem-interest   "#93c5fd"
   :rem-fees       "#c4b5fd"})

(defn- loan-total-cost [summary]
  (let [{:keys [remaining-principal remaining-interest remaining-fees
                paid-principal paid-interest paid-fees]} summary]
    (+ (or paid-principal 0) (or paid-interest 0) (or paid-fees 0)
       remaining-principal remaining-interest (or remaining-fees 0))))

(defn- loan-bar
  "Horizontal bar showing paid/remaining split into principal/interest.
   When max-total is provided, bar width is proportional to it."
  [summary & {:keys [max-total]}]
  (let [{:keys [remaining-principal remaining-interest remaining-fees
                paid-principal paid-interest paid-fees]} summary
        total (loan-total-cost summary)
        bar-width-pct (if (and max-total (pos? max-total))
                        (str (* 100.0 (/ total max-total)) "%")
                        "100%")
        pct (fn [v] (if (pos? total) (str (* 100.0 (/ v total)) "%") "0%"))
        seg (fn [value color min-pct-for-label]
                (let [w (pct value)
                      pct-num (if (pos? total) (* 100.0 (/ value total)) 0)
                      abs-pct (if (and max-total (pos? max-total))
                                (* 100.0 (/ value max-total))
                                pct-num)]
                  [:div {:style {:width w :background-color color
                                 :display "flex" :align-items "center" :justify-content "center"
                                 :overflow "hidden" :position "relative"}
                         :title (format-kr value)}
                   (when (> abs-pct 8)
                     [:span {:style {:font-size "11px" :font-weight "500" :color "#fff"
                                     :text-shadow "0 0 3px rgba(0,0,0,0.5)"
                                     :white-space "nowrap"}}
                      (format-kr value)])]))]
    [:div {:style {:display "flex" :height "28px" :width bar-width-pct :border-radius "4px"
                     :overflow "hidden" :background-color "#e5e7eb"}}
       (when (and paid-principal (pos? paid-principal))
         (seg paid-principal (:paid-principal bar-colors) 8))
       (when (and paid-interest (pos? paid-interest))
         (seg paid-interest (:paid-interest bar-colors) 8))
       (when (and paid-fees (pos? paid-fees))
         (seg paid-fees (:paid-fees bar-colors) 8))
       (seg remaining-principal (:rem-principal bar-colors) 8)
       (seg remaining-interest (:rem-interest bar-colors) 8)
       (when (and remaining-fees (pos? remaining-fees))
         (seg remaining-fees (:rem-fees bar-colors) 8))]))

(defn- loan-bar-legend []
  [:div {:style {:display "flex" :gap "16px" :margin-top "8px" :font-size "12px" :color "#555"}}
   (for [[k label] [[:paid-principal "Betalt avdrag"] [:paid-interest "Betalt rente"] [:paid-fees "Betalte gebyr"]
                     [:rem-principal "Gjenstående avdrag"] [:rem-interest "Gjenstående rente"] [:rem-fees "Gjenstående gebyr"]]]
     ^{:key k}
     [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
      [:div {:style {:width "12px" :height "12px" :border-radius "2px"
                     :background-color (get bar-colors k)}}]
      [:span label]])])

(defn- format-kr [n]
  (str (gstring/format "%.0f" n) " kr"))

(defn- format-duration [months]
  (let [y (quot months 12)
        m (rem months 12)]
    (cond
      (zero? y) (str m " mnd")
      (zero? m) (str y " år")
      :else (str y " år " m " mnd"))))

(defn- loan-list-view [loans view-state]
  [:div
   [:div {:style {:display "flex" :justify-content "space-between" :align-items "center" :margin-bottom "16px"}}
    [:h3 {:style {:margin 0}} "Lån"]
    [:button {:on-click #(reset! view-state {:view :new})
              :style {:padding "6px 16px" :background-color "#2563eb" :color "#fff"
                      :border "none" :border-radius "4px" :cursor "pointer" :font-size "13px"}}
     "Nytt lån"]]
   [loan-bar-legend]
   (if (empty? loans)
     [:p {:style {:color "#888" :margin-top "24px"}} "Ingen lån lagt til ennå."]
     (let [summaries (->> loans
                         (mapv (fn [l] [l (loan-svc/loan-summary l)]))
                         (sort-by (fn [[_ s]] (+ (or (:paid-principal s) 0) (or (:paid-interest s) 0))) >))
           max-total (apply max (map (fn [[_ s]] (loan-total-cost s)) summaries))]
       [:div {:style {:margin-top "16px" :display "flex" :flex-direction "column" :gap "12px"}}
        (doall
         (for [[loan summary] summaries]
           ^{:key (:id loan)}
           [:div {:style {:border "1px solid #ddd" :border-radius "8px" :padding "12px"
                          :cursor "pointer" :transition "box-shadow 0.15s"}}
            [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"
                           :margin-bottom "8px"}
                   :on-click #(reset! view-state {:view :detail :loan loan})}
             [:div
              [:div {:style {:font-weight "600" :font-size "15px"}} (:name loan)]
              [:div {:style {:font-size "12px" :color "#666"}}
               (str (gstring/format "%.2f" (* 100 (:nominal-rate loan))) "% · "
                    (format-kr (:total-remaining summary)) " gjenstående · "
                    (format-duration (:remaining-months summary)) " igjen")]]
             [:button {:on-click (fn [e]
                                   (.stopPropagation e)
                                   (when (js/confirm "Slett dette lånet?")
                                     (dispatch [:delete-loan (:id loan)])))
                       :style {:background "none" :border "none" :color "#c33"
                               :cursor "pointer" :font-size "13px"}}
              "Slett"]]
            [:div {:on-click #(reset! view-state {:view :detail :loan loan})}
             [loan-bar summary :max-total max-total]]]))]))])

(defn- loan-detail-chart
  "Draw D3 stacked bar chart of amortization schedule, including paid portion."
  [summary]
  (let [container-id (str "loan-chart-" (random-uuid))]
    (r/create-class
     {:display-name "loan-detail-chart"
      :component-did-mount
      (fn [this]
        (let [paid-schedule (or (:paid-schedule summary) [])
              remaining-schedule (:schedule summary)
              ;; Tag each entry with :phase
              all-data (vec (concat
                             (map-indexed (fn [i m] (assoc m :idx i :phase :paid)) paid-schedule)
                             (map-indexed (fn [i m] (assoc m :idx (+ (count paid-schedule) i) :phase :remaining)) remaining-schedule)))
              el (.getElementById js/document container-id)
              w (min 900 (.-offsetWidth el))
              h 350
              margin {:top 20 :right 20 :bottom 40 :left 60}
              inner-w (- w (:left margin) (:right margin))
              inner-h (- h (:top margin) (:bottom margin))
              svg (-> d3 (.select (str "#" container-id))
                      (.append "svg")
                      (.attr "width" w)
                      (.attr "height" h))
              g (-> svg (.append "g")
                    (.attr "transform" (str "translate(" (:left margin) "," (:top margin) ")")))
              ;; Tooltip div
              tooltip (-> d3 (.select "body")
                          (.append "div")
                          (.attr "class" "loan-tooltip")
                          (.style "position" "absolute")
                          (.style "background" "rgba(0,0,0,0.8)")
                          (.style "color" "#fff")
                          (.style "padding" "6px 10px")
                          (.style "border-radius" "4px")
                          (.style "font-size" "12px")
                          (.style "pointer-events" "none")
                          (.style "opacity" 0)
                          (.style "z-index" 1000))
              fee (or (:monthly-fee summary) 0)
              js-data (clj->js all-data)
              paid-count (count paid-schedule)
              today (js/Date.)
              today-y (.getFullYear today)
              today-m0 (.getMonth today)
              month-names ["jan" "feb" "mar" "apr" "mai" "jun"
                           "jul" "aug" "sep" "okt" "nov" "des"]
              idx->ym (fn [idx]
                        (let [offset (- idx paid-count)
                              total (+ (* today-y 12) today-m0 offset)
                              y (quot total 12)
                              m0 (mod total 12)]
                          [y m0]))
              x-scale (-> d3 (.scaleBand)
                          (.domain (clj->js (map :idx all-data)))
                          (.range #js [0 inner-w])
                          (.padding 0.1))
              max-y (apply max (map #(+ (:principal %) (:interest %) fee) all-data))
              y-scale (-> d3 (.scaleLinear)
                          (.domain #js [0 max-y])
                          (.range #js [inner-h 0]))
              show-tip (fn [event d]
                         (let [phase (if (= (.-phase d) "paid") "Betalt" "Gjenstående")
                               [y m0] (idx->ym (.-idx d))
                               label (str (nth month-names m0) " " y)
                               principal (.-principal d)
                               interest (.-interest d)]
                           (-> tooltip
                               (.html (str "<b>" label " (" phase ")</b><br>"
                                           "Avdrag: " (.toFixed principal 0) " kr<br>"
                                           "Rente: " (.toFixed interest 0) " kr"
                                           (when (pos? fee)
                                             (str "<br>Gebyr: " fee " kr"))))
                               (.style "opacity" 1)
                               (.style "left" (str (+ (.-pageX event) 12) "px"))
                               (.style "top" (str (- (.-pageY event) 28) "px")))))
              hide-tip (fn [] (.style tooltip "opacity" 0))
              principal-color (fn [d] (if (= (.-phase d) "paid")
                                        (:paid-principal bar-colors)
                                        (:rem-principal bar-colors)))
              interest-color (fn [d] (if (= (.-phase d) "paid")
                                       (:paid-interest bar-colors)
                                       (:rem-interest bar-colors)))]
          ;; Principal bars (bottom)
          (-> g (.selectAll ".bar-principal")
              (.data js-data)
              (.enter) (.append "rect")
              (.attr "x" (fn [d] (.call x-scale nil (.-idx d))))
              (.attr "y" (fn [d] (.call y-scale nil (.-principal d))))
              (.attr "width" (.bandwidth x-scale))
              (.attr "height" (fn [d] (- inner-h (.call y-scale nil (.-principal d)))))
              (.attr "fill" principal-color)
              (.on "mouseover" show-tip)
              (.on "mousemove" show-tip)
              (.on "mouseout" hide-tip))
          ;; Interest bars (stacked on top)
          (-> g (.selectAll ".bar-interest")
              (.data js-data)
              (.enter) (.append "rect")
              (.attr "x" (fn [d] (.call x-scale nil (.-idx d))))
              (.attr "y" (fn [d] (.call y-scale nil (+ (.-principal d) (.-interest d)))))
              (.attr "width" (.bandwidth x-scale))
              (.attr "height" (fn [d] (- inner-h (.call y-scale nil (.-interest d)))))
              (.attr "fill" interest-color)
              (.on "mouseover" show-tip)
              (.on "mousemove" show-tip)
              (.on "mouseout" hide-tip))
          ;; Fee bars (stacked on top of interest)
          (when (pos? fee)
            (let [fee-color (fn [d] (if (= (.-phase d) "paid")
                                      (:paid-fees bar-colors)
                                      (:rem-fees bar-colors)))]
              (-> g (.selectAll ".bar-fee")
                  (.data js-data)
                  (.enter) (.append "rect")
                  (.attr "x" (fn [d] (.call x-scale nil (.-idx d))))
                  (.attr "y" (fn [^js d] (.call y-scale nil (+ (.-principal d) (.-interest d) fee))))
                  (.attr "width" (.bandwidth x-scale))
                  (.attr "height" (- inner-h (.call y-scale nil fee)))
                  (.attr "fill" fee-color)
                  (.on "mouseover" show-tip)
                  (.on "mousemove" show-tip)
                  (.on "mouseout" hide-tip))))
          ;; Divider line between paid and remaining
          (when (seq paid-schedule)
            (let [divider-x (* (count paid-schedule) (/ inner-w (count all-data)))]
              (-> g (.append "line")
                  (.attr "x1" divider-x) (.attr "x2" divider-x)
                  (.attr "y1" 0) (.attr "y2" inner-h)
                  (.attr "stroke" "#333") (.attr "stroke-width" 1.5)
                  (.attr "stroke-dasharray" "4,3"))
              (-> g (.append "text")
                  (.attr "x" (- divider-x 4)) (.attr "y" 12)
                  (.attr "text-anchor" "end")
                  (.attr "font-size" "11px") (.attr "fill" "#555")
                  (.text "Betalt"))
              (-> g (.append "text")
                  (.attr "x" (+ divider-x 4)) (.attr "y" 12)
                  (.attr "text-anchor" "start")
                  (.attr "font-size" "11px") (.attr "fill" "#555")
                  (.text "Gjenstående"))))
          ;; X axis — labels at January boundaries; for short schedules show month abbreviations
          (let [total (count all-data)
                use-months? (< total 12)
                x-axis (if use-months?
                         (-> d3 (.axisBottom x-scale)
                             (.tickValues (clj->js (map :idx all-data)))
                             (.tickFormat (fn [idx]
                                            (let [[_ m0] (idx->ym idx)]
                                              (nth month-names m0)))))
                         (let [year-tick-idxs (vec (filter
                                                    (fn [idx] (zero? (second (idx->ym idx))))
                                                    (range total)))]
                           (-> d3 (.axisBottom x-scale)
                               (.tickValues (clj->js year-tick-idxs))
                               (.tickFormat (fn [idx] (str (first (idx->ym idx))))))))]
            (-> g (.append "g")
                (.attr "transform" (str "translate(0," inner-h ")"))
                (.call x-axis)))
          ;; Y axis
          (-> g (.append "g")
              (.call (-> d3 (.axisLeft y-scale) (.ticks 6))))))
      :component-will-unmount
      (fn [_]
        (-> d3 (.selectAll ".loan-tooltip") (.remove)))
      :reagent-render
      (fn [_]
        [:div {:id container-id :style {:width "100%"}}])})))

(defn- format-duration-capped
  "Like format-duration, but renders > 100 år when the schedule hit the iteration cap."
  [months]
  (if (>= months 1200)
    "> 100 år"
    (format-duration months)))

(defn- parse-num-or [s fallback]
  (let [n (js/parseFloat s)]
    (if (js/isNaN n) fallback n)))

(defn- delta-row [label cur sim format-fn]
  (let [d (- sim cur)
        sign (cond (pos? d) "+" (neg? d) "-" :else "±")
        abs-d (Math/abs d)]
    [:tr
     [:td {:style {:padding "2px 12px 2px 0" :color "#555"}} label]
     [:td {:style {:padding "2px 8px" :text-align "right"}} (format-fn cur)]
     [:td {:style {:padding "2px 4px" :color "#aaa"}} "->"]
     [:td {:style {:padding "2px 8px" :text-align "right"}} (format-fn sim)]
     [:td {:style {:padding "2px 0 2px 12px" :text-align "right"
                   :color (cond (neg? d) "#15803d" (pos? d) "#b91c1c" :else "#888")}}
      (str sign " " (format-fn abs-d))]]))

(defn- loan-simulator [loan summary]
  (let [initial {:rate-pct (gstring/format "%.3f" (* 100 (:nominal-rate loan)))
                 :monthly-payment (str (:monthly-payment loan))
                 :lump-sum "0"}
        sim (r/atom initial)]
    (fn [loan summary]
      (let [rate-pct (parse-num-or (:rate-pct @sim) (* 100 (:nominal-rate loan)))
            payment (parse-num-or (:monthly-payment @sim) (:monthly-payment loan))
            lump (max 0 (parse-num-or (:lump-sum @sim) 0))
            sim-balance (max 0 (- (:balance loan) lump))
            sim-nominal-rate (/ rate-pct 100.0)
            sim-loan (-> loan
                         (assoc :nominal-rate sim-nominal-rate)
                         (assoc :monthly-payment payment)
                         (assoc :balance sim-balance)
                         (dissoc :original-amount))
            sim-summary (-> (loan-svc/loan-summary sim-loan)
                            ;; Splice in the original loan's paid history so both
                            ;; charts share a static left side. The paid portion
                            ;; doesn't depend on simulated inputs.
                            (assoc :paid-schedule (:paid-schedule summary)))
            monthly-fee (or (:monthly-fee loan) 0)
            r-monthly (loan-svc/monthly-rate sim-nominal-rate)
            min-payment (+ (* sim-balance r-monthly) monthly-fee)
            payment-too-low? (<= (- payment monthly-fee) (* sim-balance r-monthly))
            cur-months (:remaining-months summary)
            cur-interest (:remaining-interest summary)
            cur-total (:total-remaining summary)
            sim-months (:remaining-months sim-summary)
            sim-interest (:remaining-interest sim-summary)
            sim-total (:total-remaining sim-summary)
            input-style {:width "120px" :padding "6px 8px"
                         :border "1px solid #ccc" :border-radius "4px"
                         :font-size "13px"}
            label-style {:font-size "12px" :color "#555" :display "flex"
                         :flex-direction "column" :gap "2px"}]
        [:div {:style {:margin-top "32px"}}
         [:h4 {:style {:margin "0 0 12px"}} "Simuler"]
         [:div {:style {:display "flex" :flex-wrap "wrap" :gap "16px" :align-items "flex-end"
                        :margin-bottom "12px"}}
          [:label {:style label-style}
           "Rente (%)"
           [:input {:type "number" :step "0.01" :value (:rate-pct @sim)
                    :on-change #(swap! sim assoc :rate-pct (-> % .-target .-value))
                    :style input-style}]]
          [:label {:style label-style}
           "Månedlig betaling (kr)"
           [:input {:type "number" :step "100" :value (:monthly-payment @sim)
                    :on-change #(swap! sim assoc :monthly-payment (-> % .-target .-value))
                    :style input-style}]]
          [:label {:style label-style}
           "Engangsinnbetaling (kr)"
           [:input {:type "number" :step "1000" :value (:lump-sum @sim)
                    :on-change #(swap! sim assoc :lump-sum (-> % .-target .-value))
                    :style input-style}]]
          [:button {:on-click #(reset! sim initial)
                    :style {:padding "6px 14px" :background "none"
                            :border "1px solid #ccc" :border-radius "4px" :cursor "pointer"
                            :font-size "13px" :height "32px"}}
           "Tilbakestill"]]
         (when payment-too-low?
           [:div {:style {:color "#b91c1c" :font-size "12px" :margin-bottom "12px"}}
            (str "Betalingen er for lav til å betjene renten. Minimum ca "
                 (format-kr min-payment) "/mnd.")])
         [:h4 {:style {:margin "8px 0"}} "Simulert"]
         ^{:key (str rate-pct "-" payment "-" lump)}
         [loan-detail-chart sim-summary]
         [:table {:style {:margin-top "12px" :font-size "13px" :border-collapse "collapse"}}
          [:thead
           [:tr {:style {:color "#888" :font-size "11px" :text-transform "uppercase"}}
            [:th {:style {:text-align "left" :padding "2px 12px 6px 0"}} "Sammenligning"]
            [:th {:style {:text-align "right" :padding "2px 8px 6px"}} "Nåværende"]
            [:th]
            [:th {:style {:text-align "right" :padding "2px 8px 6px"}} "Simulert"]
            [:th {:style {:text-align "right" :padding "2px 0 6px 12px"}} "Endring"]]]
          [:tbody
           [delta-row "Lengde" cur-months sim-months format-duration-capped]
           [delta-row "Total rente" cur-interest sim-interest format-kr]
           [delta-row "Total gjenværende" cur-total sim-total format-kr]]]]))))

(defn- format-date-ms [ms]
  (let [d (js/Date. ms)
        y (.getFullYear d)
        mo (gstring/format "%02d" (inc (.getMonth d)))
        da (gstring/format "%02d" (.getDate d))]
    (str y "-" mo "-" da)))

(defn- format-month-ms [ms]
  (let [d (js/Date. ms)]
    (str (.getFullYear d) "-" (gstring/format "%02d" (inc (.getMonth d))))))

(defn- format-pct [r]
  (str (gstring/format "%.3f" (* 100 r)) "%"))

(defn loan-history-init-state
  "Build initial state for the history editor from a transaction list and optional
   stored history (used when reopening the editor on an existing loan)."
  ([txns] (loan-history-init-state txns nil 0.06))
  ([txns existing-history threshold]
   (let [changes (loan-svc/extract-changes txns)
         ;; existing-history segments after the first correspond 1-to-1 with changes
         existing-types (when (and existing-history (= (count existing-history)
                                                       (inc (count changes))))
                          (mapv (fn [seg]
                                  (let [t (:type seg)]
                                    (if (keyword? t) t (keyword (str t)))))
                                (rest existing-history)))]
     {:threshold threshold
      :classifications
      (into {} (map-indexed
                (fn [i ch]
                  [i (or (get existing-types i)
                         (loan-svc/classify-change ch threshold))])
                changes))})))

(defn- loan-history-editor
  "Render the change-classification table and derive a payment-history vector.

   Props:
     state-atom   — owner-supplied ratom holding {:threshold :classifications}
     loan-params  — {:nominal-rate :monthly-payment :monthly-fee :balance :original-amount}
     txns         — vector of matching transactions (each has :date and :amount)

   Side note: derived segments are recomputed on every render. The caller re-derives
   at save time using the same inputs, so the editor doesn't need to expose them."
  [state-atom loan-params txns]
  (let [changes (loan-svc/extract-changes txns)
        {:keys [threshold classifications]} @state-atom
        today-ms (.getTime (js/Date.))
        derived (when (and (:nominal-rate loan-params)
                           (:monthly-payment loan-params)
                           (:balance loan-params)
                           (seq txns))
                  (loan-svc/derive-history loan-params txns classifications threshold today-ms))
        segments (:segments derived)
        discrepancy (:discrepancy derived)
        ;; segment-by-change-index: change i corresponds to segment (i + 1)
        seg-at (fn [i] (nth segments (inc i) nil))
        initial-seg (first segments)
        reclassify (fn [new-threshold]
                     (swap! state-atom assoc
                            :threshold new-threshold
                            :classifications
                            (into {} (map-indexed
                                      (fn [i ch] [i (loan-svc/classify-change ch new-threshold)])
                                      changes))))]
    [:div
     (cond
       (empty? txns)
       [:div {:style {:color "#888" :font-size "13px"}}
        "Ingen matchende transaksjoner."]

       (empty? changes)
       [:div {:style {:color "#888" :font-size "13px"}}
        (str "Ingen endringer oppdaget i " (count txns) " transaksjoner — alle på "
             (format-kr (Math/abs (:amount (first txns)))) ".")]

       :else
       [:div
        [:div {:style {:display "flex" :gap "8px" :align-items "center"
                       :font-size "12px" :color "#555" :margin-bottom "8px"}}
         [:span "Klassifiseringsterskel:"]
         [:input {:type "number" :step "0.5" :min 0
                  :value (gstring/format "%.1f" (* 100 threshold))
                  :on-change #(reclassify (/ (parse-num-or (-> % .-target .-value) 3) 100.0))
                  :style {:width "60px" :padding "2px 4px"
                          :border "1px solid #ccc" :border-radius "4px"
                          :font-size "12px"}}]
         [:span "%"]]
        [:table {:style {:font-size "13px" :border-collapse "collapse"}}
         [:thead
          [:tr {:style {:color "#888" :font-size "11px" :text-transform "uppercase"}}
           [:th {:style {:text-align "left"  :padding "2px 12px 6px 0"}} "Dato"]
           [:th {:style {:text-align "right" :padding "2px 8px 6px"}} "Forrige"]
           [:th]
           [:th {:style {:text-align "right" :padding "2px 8px 6px"}} "Ny"]
           [:th {:style {:text-align "right" :padding "2px 8px 6px"}} "Diff"]
           [:th {:style {:text-align "left"  :padding "2px 8px 6px"}} "Type"]
           [:th {:style {:text-align "right" :padding "2px 0 6px 12px"}} "Rente"]]]
         [:tbody
          (doall
           (map-indexed
            (fn [i ch]
              (let [t (get classifications i)
                    seg (seg-at i)
                    rate (when seg (:rate seg))]
                ^{:key i}
                [:tr
                 [:td {:style {:padding "2px 12px 2px 0" :color "#555"}}
                  (format-date-ms (:date ch))]
                 [:td {:style {:padding "2px 8px" :text-align "right"}}
                  (format-kr (:prev-amount ch))]
                 [:td {:style {:padding "2px 4px" :color "#aaa"}} "→"]
                 [:td {:style {:padding "2px 8px" :text-align "right"}}
                  (format-kr (:new-amount ch))]
                 [:td {:style {:padding "2px 8px" :text-align "right" :color "#888"}}
                  (str (if (pos? (:diff-pct ch)) "+" "")
                       (gstring/format "%.1f" (* 100 (:diff-pct ch))) "%")]
                 [:td {:style {:padding "2px 8px"}}
                  [:select {:value (name t)
                            :on-change #(swap! state-atom assoc-in
                                               [:classifications i]
                                               (keyword (-> % .-target .-value)))
                            :style {:padding "2px 4px"
                                    :border "1px solid #ccc" :border-radius "4px"
                                    :font-size "12px"}}
                   [:option {:value "interest"} "Rente"]
                   [:option {:value "payment"} "Betaling"]]]
                 [:td {:style {:padding "2px 0 2px 12px" :text-align "right" :color "#555"}}
                  (cond
                    (nil? rate)            "—"
                    (= t :interest)        (format-pct rate)
                    :else                  "—")]]))
            changes))]]
        (when initial-seg
          [:div {:style {:font-size "12px" :color "#555" :margin-top "8px"}}
           (str "Startrente (avledet): " (format-pct (:rate initial-seg))
                " · 1. transaksjon " (format-date-ms (:date initial-seg)))])
        (when (and discrepancy (> (Math/abs discrepancy) 1000))
          [:div {:style {:font-size "12px" :color "#b91c1c" :margin-top "4px"}}
           (str "Startbalansen avviker fra opprinnelig lånebeløp med "
                (format-kr discrepancy) ".")])])]))

(defn loan-history-derive-segments
  "Helper for callers (loan-form, detail view) to compute the :payment-history vector
   from the editor's state at save time. Returns nil when inputs are insufficient.

   Converts :type keywords to strings so the value JSON-serializes cleanly without
   leading colons leaking through to storage."
  [state loan-params txns]
  (when (and (:nominal-rate loan-params)
             (:monthly-payment loan-params)
             (:balance loan-params)
             (seq txns))
    (some->> (:segments (loan-svc/derive-history loan-params txns
                                                 (:classifications state)
                                                 (:threshold state)
                                                 (.getTime (js/Date.))))
             (mapv #(update % :type (fn [t] (if (keyword? t) (name t) t)))))))

(defn- type-label [t]
  (let [k (if (keyword? t) t (keyword (str t)))]
    (case k
      :interest "Rente"
      :payment  "Betaling"
      :initial  "Start"
      (name k))))

(defn- loan-history-display
  "Read-only segment listing for the detail view."
  [history]
  [:div {:style {:font-size "13px" :color "#333"}}
   (doall
    (for [[i seg] (map-indexed vector history)]
      ^{:key i}
      [:div {:style {:display "flex" :gap "12px" :padding "3px 0"}}
       [:span {:style {:color "#888" :min-width "80px"}}
        (format-month-ms (:date seg))]
       [:span {:style {:min-width "70px"}} (type-label (:type seg))]
       [:span {:style {:color "#555" :min-width "120px"}}
        (str (format-kr (:amount seg)) " /mnd")]
       [:span {:style {:color "#555"}}
        (when (:rate seg) (str "rente " (format-pct (:rate seg))))]]))])

(defn- loan-detail-view [loan view-state]
  (let [editing-history? (r/atom false)
        history-state (r/atom nil)
        update-feedback (r/atom nil)]
    (fn [loan view-state]
      (let [summary (loan-svc/loan-summary loan)
            history (:payment-history loan)
            all-txns @(subscribe [:all-transactions])
            filter-texts (or (:filter-texts loan)
                             (when (seq (:filter-text loan))
                               [(:filter-text loan)])
                             [])
            matching-txns (when (seq filter-texts)
                            (vec (->> all-txns
                                      (filter (fn [tx]
                                                (some #(category/match-fun (:description tx) %)
                                                      filter-texts)))
                                      (sort-by :date))))
            loan-params {:nominal-rate (:nominal-rate loan)
                         :monthly-payment (:monthly-payment loan)
                         :monthly-fee (or (:monthly-fee loan) 0)
                         :balance (:balance loan)
                         :original-amount (:original-amount loan)}]
        [:div
         [:div {:style {:display "flex" :gap "8px" :margin-bottom "16px"}}
          [:button {:on-click #(reset! view-state {:view :list})
                    :style {:padding "4px 12px" :background "none"
                            :border "1px solid #ccc" :border-radius "4px" :cursor "pointer"
                            :font-size "13px"}}
           "\u2190 Tilbake"]
      [:button {:on-click #(reset! view-state {:view :edit :loan loan})
                :style {:padding "4px 12px" :background "none"
                        :border "1px solid #ccc" :border-radius "4px" :cursor "pointer"
                        :font-size "13px"}}
       "Rediger"]]
     [:h3 {:style {:margin "0 0 8px"}} (:name loan)]
     [:div {:style {:display "flex" :gap "24px" :font-size "13px" :color "#555" :margin-bottom "12px"}}
      [:span (str "Rente: " (gstring/format "%.2f" (* 100 (:nominal-rate loan))) "%")]
      [:span (str "Saldo: " (format-kr (:balance loan)))]
      [:span (str "Terminbeløp: " (format-kr (:monthly-payment loan)))]
      [:span (str "Gjenstående: " (format-duration (:remaining-months summary)))]
      [:span (str "Total rente igjen: " (format-kr (:remaining-interest summary)))]
      (when (pos? (or (:remaining-fees summary) 0))
        [:span (str "Totale gebyr igjen: " (format-kr (:remaining-fees summary)))])]
     [loan-bar summary]
     [loan-bar-legend]
     [:h4 {:style {:margin-top "24px" :margin-bottom "8px"}} "Terminplan"]
     [:div {:style {:display "flex" :gap "16px" :margin-bottom "8px" :font-size "12px" :color "#555"}}
      [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
       [:div {:style {:width "12px" :height "12px" :background-color (:rem-principal bar-colors)}}]
       "Avdrag"]
      [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
       [:div {:style {:width "12px" :height "12px" :background-color (:rem-interest bar-colors)}}]
       "Rente"]
      (when (pos? (or (:monthly-fee summary) 0))
        [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
         [:div {:style {:width "12px" :height "12px" :background-color (:rem-fees bar-colors)}}]
         "Gebyr"])]
     [:h4 {:style {:margin "8px 0"}} "Nåværende"]
     [loan-detail-chart summary]
     (when (or (seq history) (seq matching-txns))
       [:div {:style {:margin-top "24px"}}
        [:h4 {:style {:margin "0 0 8px"}} "Historikk"]
        (if @editing-history?
          [:div
           [loan-history-editor history-state loan-params matching-txns]
           [:div {:style {:display "flex" :gap "8px" :margin-top "12px"}}
            [:button {:on-click (fn []
                                  (let [segs (loan-history-derive-segments
                                              @history-state loan-params matching-txns)]
                                    (dispatch [:store-loan
                                               (cond-> loan
                                                 (seq segs) (assoc :payment-history segs))])
                                    (reset! editing-history? false)))
                      :style {:padding "6px 14px" :background-color "#2563eb" :color "#fff"
                              :border "none" :border-radius "4px" :cursor "pointer"
                              :font-size "13px"}}
             "Lagre"]
            [:button {:on-click #(reset! editing-history? false)
                      :style {:padding "6px 14px" :background "none" :color "#333"
                              :border "1px solid #ccc" :border-radius "4px" :cursor "pointer"
                              :font-size "13px"}}
             "Avbryt"]]]
          [:div
           (if (seq history)
             [loan-history-display history]
             [:div {:style {:color "#888" :font-size "13px"}}
              "Ingen lagret historikk."])
           (when (seq matching-txns)
             [:div {:style {:display "flex" :gap "8px" :margin-top "8px"}}
              [:button {:on-click (fn []
                                    (reset! history-state
                                            (loan-history-init-state matching-txns history 0.06))
                                    (reset! editing-history? true))
                        :style {:padding "4px 12px" :background "none"
                                :border "1px solid #ccc" :border-radius "4px" :cursor "pointer"
                                :font-size "13px"}}
               "Endre historikk"]
              [:button {:disabled (= @update-feedback :working)
                        :on-click (fn []
                                    (reset! update-feedback :working)
                                    (let [fresh-state (loan-history-init-state
                                                       matching-txns history 0.06)
                                          segs (loan-history-derive-segments
                                                fresh-state loan-params matching-txns)
                                          changed? (and (seq segs)
                                                        (not= segs (:payment-history loan)))]
                                      (when changed?
                                        (dispatch [:store-loan
                                                   (assoc loan :payment-history segs)]))
                                      (reset! update-feedback
                                              (if changed? :updated :unchanged))
                                      (js/setTimeout
                                       #(reset! update-feedback nil)
                                       2500)))
                        :style {:padding "4px 12px" :background "none"
                                :border "1px solid #ccc" :border-radius "4px"
                                :cursor (if (= @update-feedback :working) "default" "pointer")
                                :font-size "13px"
                                :opacity (if (= @update-feedback :working) 0.6 1)}}
               (case @update-feedback
                 :working   "Oppdaterer..."
                 :updated   "✓ Oppdatert"
                 :unchanged "Ingen endringer"
                 "Oppdater historikk")]])])])
     [loan-simulator loan summary]]))))

(defn- loan-form [view-state & [{:keys [editing-loan]}]]
  (let [form (r/atom (if editing-loan
                       {:name (:name editing-loan "")
                        :nominal-rate (str (:nominal-rate editing-loan ""))
                        :balance (str (:balance editing-loan ""))
                        :monthly-payment (str (:monthly-payment editing-loan ""))
                        :monthly-fee (str (or (:monthly-fee editing-loan) ""))
                        :original-amount (str (or (:original-amount editing-loan) ""))
                        :interest-method (or (:interest-method editing-loan) "simple")
                        :filter-texts (or (:filter-texts editing-loan)
                                          (when (seq (:filter-text editing-loan))
                                            [(:filter-text editing-loan)])
                                          [])}
                       {:name "" :nominal-rate "" :balance "" :monthly-payment ""
                        :monthly-fee "" :original-amount ""
                        :interest-method "simple"
                        :filter-texts []}))
        history-state (r/atom nil)
        existing-history (when editing-loan (:payment-history editing-loan))]
    (fn [view-state & [{:keys [editing-loan]}]]
      (let [parse-num (fn [s] (let [n (js/parseFloat s)] (when-not (js/isNaN n) n)))
            tags @(subscribe [:tags])
            categories @(subscribe [:categories])
            all-txns @(subscribe [:all-transactions])
            loan-tag-ids (set (keep #(when (re-find #"(?i)l.n|loan" (or (:name %) ""))
                                       (:id %))
                                    tags))
            loan-filters (when (seq loan-tag-ids)
                           (->> categories
                                (mapcat (fn [cat]
                                          (->> (:filters cat)
                                               (filter (fn [f] (some loan-tag-ids (or (:tag-ids f) []))))
                                               (map #(assoc % :category-name (:name cat))))))
                                (distinct)))
            current-filter-texts (or (:filter-texts @form) [])
            matching-txns (when (seq current-filter-texts)
                            (vec (->> all-txns
                                      (filter (fn [tx]
                                                (some #(category/match-fun (:description tx) %)
                                                      current-filter-texts)))
                                      (sort-by :date))))
            _ (when (and (nil? @history-state) (seq matching-txns))
                (reset! history-state
                        (loan-history-init-state matching-txns existing-history 0.06)))
            filter-options (when (seq loan-filters)
                             (->> loan-filters
                                  (keep (fn [f]
                                          (let [matching (->> all-txns
                                                              (filter #(category/match-fun (:description %)
                                                                                           (:text f)))
                                                              (sort-by :date)
                                                              reverse)
                                                latest (first matching)]
                                            (when latest
                                              {:label (str (:category-name f) " / " (:text f)
                                                           " — " (Math/abs (:amount latest)) " kr")
                                               :amount (Math/abs (:amount latest))
                                               :text (:text f)}))))))
            loan-params {:nominal-rate (parse-num (:nominal-rate @form))
                         :monthly-payment (parse-num (:monthly-payment @form))
                         :monthly-fee (or (parse-num (:monthly-fee @form)) 0)
                         :balance (parse-num (:balance @form))
                         :original-amount (parse-num (:original-amount @form))}
            valid? (and (seq (:name @form))
                        (parse-num (:nominal-rate @form))
                        (parse-num (:balance @form))
                        (parse-num (:monthly-payment @form)))]
        [:div
         [:h3 {:style {:margin "0 0 16px"}} (if editing-loan "Rediger lån" "Nytt lån")]
         [:div {:style {:max-width "400px" :display "flex" :flex-direction "column" :gap "10px"}}
          [:label {:style {:font-size "13px" :color "#555"}} "Navn"
           [:input {:type "text" :value (:name @form)
                    :on-change #(swap! form assoc :name (-> % .-target .-value))
                    :style {:display "block" :width "100%" :padding "6px 8px" :margin-top "2px"
                            :border "1px solid #ccc" :border-radius "4px"}}]]
          [:label {:style {:font-size "13px" :color "#555"}} "Nominell rente (f.eks. 0.045 for 4.5%)"
           [:input {:type "number" :step "0.001" :value (:nominal-rate @form)
                    :on-change #(swap! form assoc :nominal-rate (-> % .-target .-value))
                    :style {:display "block" :width "100%" :padding "6px 8px" :margin-top "2px"
                            :border "1px solid #ccc" :border-radius "4px"}}]]
          [:label {:style {:font-size "13px" :color "#555"}} "Gjenstående saldo"
           [:input {:type "number" :value (:balance @form)
                    :on-change #(swap! form assoc :balance (-> % .-target .-value))
                    :style {:display "block" :width "100%" :padding "6px 8px" :margin-top "2px"
                            :border "1px solid #ccc" :border-radius "4px"}}]]
          [:label {:style {:font-size "13px" :color "#555"}} "Månedlig betaling"
           [:input {:type "number" :value (:monthly-payment @form)
                    :on-change #(swap! form assoc :monthly-payment (-> % .-target .-value))
                    :style {:display "block" :width "100%" :padding "6px 8px" :margin-top "2px"
                            :border "1px solid #ccc" :border-radius "4px"}}]]
          (when (seq filter-options)
            [:div {:style {:font-size "12px" :color "#666" :margin-top "-4px"}}
             "Eller velg fra lån-taggede filtre:"
             [:div {:style {:display "flex" :flex-direction "column" :gap "4px" :margin-top "4px"}}
              (for [[i opt] (map-indexed vector filter-options)]
                ^{:key i}
                (let [text (:text opt)
                      checked? (some #{text} current-filter-texts)]
                  [:label {:style {:display "flex" :align-items "center" :gap "6px" :cursor "pointer"}}
                   [:input {:type "checkbox"
                            :checked (boolean checked?)
                            :on-change (fn [_]
                                         (let [new-texts (if checked?
                                                           (vec (remove #{text} current-filter-texts))
                                                           (conj (vec current-filter-texts) text))
                                               matching (when (seq new-texts)
                                                          (vec (->> all-txns
                                                                    (filter (fn [tx]
                                                                              (some #(category/match-fun (:description tx) %)
                                                                                    new-texts)))
                                                                    (sort-by :date))))
                                               latest (last matching)]
                                           (swap! form assoc :filter-texts new-texts)
                                           (when latest
                                             (swap! form assoc :monthly-payment
                                                    (str (Math/abs (:amount latest)))))
                                           (reset! history-state
                                                   (when (seq matching)
                                                     (loan-history-init-state matching nil 0.06)))))}]
                   [:span (:label opt)]]))]])
          [:label {:style {:font-size "13px" :color "#555"}} "Månedlig gebyr (valgfritt)"
           [:input {:type "number" :value (:monthly-fee @form)
                    :on-change #(swap! form assoc :monthly-fee (-> % .-target .-value))
                    :style {:display "block" :width "100%" :padding "6px 8px" :margin-top "2px"
                            :border "1px solid #ccc" :border-radius "4px"}}]]
          [:label {:style {:font-size "13px" :color "#555"}} "Opprinnelig lånebeløp (valgfritt)"
           [:input {:type "number" :value (:original-amount @form)
                    :on-change #(swap! form assoc :original-amount (-> % .-target .-value))
                    :style {:display "block" :width "100%" :padding "6px 8px" :margin-top "2px"
                            :border "1px solid #ccc" :border-radius "4px"}}]]
          [:label {:style {:font-size "13px" :color "#555"}} "Renteberegning"
           [:select {:value (:interest-method @form)
                     :on-change #(swap! form assoc :interest-method (-> % .-target .-value))
                     :style {:display "block" :width "100%" :padding "6px 8px" :margin-top "2px"
                             :border "1px solid #ccc" :border-radius "4px"}}
            [:option {:value "simple"} "Forenklet (nominell / 12 per måned)"]
            [:option {:value "actual"} "Bankberegning (faktiske dager / 365)"]]]]
         (when (and (seq matching-txns) (some? @history-state))
           [:div {:style {:margin-top "16px" :max-width "720px"}}
            [:h4 {:style {:margin "0 0 8px"}} "Betalingshistorikk"]
            [loan-history-editor history-state loan-params matching-txns]])
         [:div {:style {:max-width "400px"}}
          [:div {:style {:display "flex" :gap "8px" :margin-top "16px"}}
           [:button {:disabled (not valid?)
                     :on-click (fn []
                                 (let [history-segments
                                       (when (and @history-state (seq matching-txns))
                                         (loan-history-derive-segments @history-state
                                                                       loan-params
                                                                       matching-txns))
                                       loan (cond-> {:name (:name @form)
                                                     :nominal-rate (parse-num (:nominal-rate @form))
                                                     :balance (parse-num (:balance @form))
                                                     :monthly-payment (parse-num (:monthly-payment @form))
                                                     :interest-method (or (:interest-method @form) "simple")}
                                              editing-loan (assoc :id (:id editing-loan))
                                              (parse-num (:monthly-fee @form))
                                              (assoc :monthly-fee (parse-num (:monthly-fee @form)))
                                              (parse-num (:original-amount @form))
                                              (assoc :original-amount (parse-num (:original-amount @form)))
                                              (seq (:filter-texts @form))
                                              (assoc :filter-texts (vec (:filter-texts @form)))
                                              (seq history-segments)
                                              (assoc :payment-history history-segments))]
                                   (dispatch [:store-loan loan])
                                   (reset! view-state {:view :list})))
                     :style {:padding "6px 16px" :background-color "#2563eb" :color "#fff"
                             :border "none" :border-radius "4px" :cursor "pointer" :font-size "13px"
                             :opacity (if valid? 1 0.5)}}
            "Lagre"]
           [:button {:on-click #(reset! view-state {:view :list})
                     :style {:padding "6px 16px" :background-color "#eee" :color "#333"
                             :border "none" :border-radius "4px" :cursor "pointer" :font-size "13px"}}
            "Avbryt"]]]]))))

(defn- loans-content []
  (let [view-state (r/atom {:view :list})]
    (fn []
      (let [loans (or @(subscribe [:loans]) [])]
        [:div {:style {:padding "16px"}}
         (case (:view @view-state)
           :list [loan-list-view loans view-state]
           :new [loan-form view-state]
           :edit [loan-form view-state {:editing-loan (:loan @view-state)}]
           :detail [loan-detail-view (:loan @view-state) view-state]
           [loan-list-view loans view-state])]))))

;; ---------- User ----------

(defn- user-info-bar []
  [:div {:style {:display "flex" :align-items "center" :justify-content "flex-end"
                 :padding "8px 16px" :border-bottom "1px solid #eee"}}
   [user-menu]])

(defn odin-app []
  (let [auth @(subscribe [:auth])]
    (if (or (nil? (:token auth)) (and (:loading? auth) (nil? (:user auth))))
      (if (:loading? auth)
        [:div {:style {:display "flex" :justify-content "center" :margin-top "200px"}}
         [:p "Laster..."]]
        [auth-view])
      (let [active-menu @(subscribe [:active-menu])]
        [:div
         [user-info-bar]
         [:div {:style {:display "flex"}}
          [nav-sidebar]
          [:div {:style {:max-width "1024px" :width "100%" :padding "0 16px"}}
           (case active-menu
             :transaksjoner [transaksjoner-content]
             :budsjett [budsjett-content]
             :laan [loans-content]
             :kontoer [kontoer-content]
             :rapporter [reports/reports-content]
             [transaksjoner-content])]]]))))
