(ns client.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
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
            [client.components.period-selector-v2.views :as period-sel-v2]
            [client.components.transactions-table-component.views :as t-table]
            [client.components.summed-table-component.views :as summed-table]
            [client.components.treemap-component.views :as treemap]
            [client.components.reports.views :as reports])
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

(defn- init-choices! [el choices-ref initial-color on-change]
  (when (and el (nil? @choices-ref))
    (let [template-fn (fn []
                        #js {:choice (fn [^js choices data]
                                       (let [class-name (.-itemChoice (.-classNames choices))
                                             data-id    (or (.-id data) "")
                                             data-value (or (.-value data) "")
                                             data-label (or (.-label data) "")
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
                                         (set! (.-innerText div-el) "")
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

(defn color-selector [{:keys [initial-color on-change]}]
  (with-let [choices-ref (r/atom nil)]
    (let [colors (map #(-> [% 0.6 0.9]
                           color/hsv2rgb
                           color/color-base10->base16
                           color/color-str)
                      (color/generate-hues 16))]
      [:div#color-selector-outer
       [:select.color-select
        {:ref #(when %
                 (init-choices! % choices-ref initial-color on-change))
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

(defn- edit-row [index category builder-category ready-to-store? show-diff? max-abs-diff]
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
      [:td {:style {:background-color "#f0f0f0"
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
      [:td {:col-span 5 :style {:background-color "#f0f0f0"
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
        builder-category @(subscribe [:builder-category])
        edit-category? (fn [cat] (= (:id cat) (:id builder-category)))
        new-category? (= (:id builder-category) "new-id")
        ready-to-store? (category/ready-to-store? builder-category)

        income-rows (mapcat identity
                     (map-indexed
                      (fn [idx cat]
                        (if (edit-category? cat)
                          (edit-row idx cat builder-category ready-to-store? false max-abs-diff)
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
                      (edit-row idx cat builder-category ready-to-store? true max-abs-diff)
                      (expense-row idx cat max-abs-diff)))
                  cats))
                [(bucket-sum-row label cats)]))))
         bucket-order)

        new-row (if new-category?
                  (edit-row 0 {:id "new-id"} builder-category ready-to-store? true max-abs-diff)
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
  (let [display-option (-> @(subscribe [:displayed-transactions-data]) :display-option)]
    [:div
     [:button {:on-click #(dispatch [:navigate [nil :table nil]])
               :style (when (= display-option :table) {:font-weight "bold"})} "Table"]
     [:button {:on-click #(dispatch [:navigate [nil :bar-chart nil]])
               :style (when (= display-option :bar-chart) {:font-weight "bold"})} "Bar-chart"]
     [:button {:on-click #(dispatch [:navigate [nil :summed-table nil]])
               :style (when (= display-option :summed-table) {:font-weight "bold"})} "Summed"]]))

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
                                :background-color "#f0f0f0"
                                :border-left-color "#333"}))
               :on-click #(dispatch [:set-active-menu k])}
           label]]))]]))

(defn- transaksjoner-content []
  [:div
   [loading-banner]
   [treemap/category-treemap {:height-ratio 0.25}]
   [period-sel-v2/period-selector]
   (filter-path)
   (displayed-transactions-view-selector)
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

(defn- kontoer-content []
  (let [accounts @(subscribe [:accounts])]
    [:div {:style {:padding "20px 0"}}
     [:h3 "Kontoer"]
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
                  :on-click #(dispatch [:delete-account (:account-id account)])}
              "Fjern"]]])]])
     [:div {:style {:margin-top "16px"}}
      [:h4 {:style {:margin-bottom "8px"}} "Koble til ny konto"]
      [:button {:on-click #(dispatch [:connect-account "sparebank1-ost" nil])
                :style {:padding "8px 16px" :background-color "#333" :color "#fff"
                        :border "none" :border-radius "4px" :cursor "pointer"
                        :font-size "14px"}}
       "Koble til Sparebank1 Østlandet"]]]))

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
             :kontoer [kontoer-content]
             :rapporter [reports/reports-content]
             [transaksjoner-content])]]]))))
