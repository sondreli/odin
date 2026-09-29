(ns client.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [clojure.string :as s]
            [client.api :as api]
            [client.services.date-service :as date]
            [client.services.color-service :as color]
            [client.services.format-service :as fmt :refer [format-kr]]
            [client.routes :as routes]
            [common.category-service :as category]
            [goog.string :as gstring]
            [goog.string.format]
            [goog.object :as g]
            ["d3" :as d3]
            [client.components.chart-component.views :as chart]
            [client.services.wealth-chart-service :as wealth-chart]
            [client.components.period-selector-component.views :as period-sel]
            [client.components.period-selector-v2.views :as period-sel-v2]
            [client.components.transactions-table-component.views :as t-table]
            [client.components.summed-table-component.views :as summed-table]
            [client.components.treemap-component.views :as treemap]
            [client.components.reports.views :as reports]
            [client.components.ui.icon :refer [icon]]
            [client.components.ui.segmented :refer [segmented]]
            [client.components.ui.button :refer [pill-button]]
            [client.components.ui.metrics-bar :refer [metrics-bar]]
            [common.loan-service :as loan-svc])
  (:require-macros [reagent.core :refer [with-let]]))

(declare transactions-search-input)
(declare multi-select-toggle)
(declare account-selector)

;; (defn period-selector []
;;     [:div
;;      [:label "Start date:"]
;;      [:input {:type "date" :value (date/first-day-of-this-month)
;;               :on-change #(dispatch [:set-period-transactions (-> % .-target .-value)])}]
;;      [:input {:type "date" :value (date/last-day-of-this-month)
;;               :on-change #(dispatch [:set-period-transactions (-> % .-target .-value)])}]]
  ;; )


(defn filter-path []
  (let [path           @(subscribe [:filter-path])
        categories     @(subscribe [:categories])
        period-txns    @(subscribe [:period-transactions])
        cat-name       (first path)
        sub-name       (second path)
        cat            (some #(when (= (:name %) cat-name) %) categories)
        cat-color      (or (:color cat) "#9ca3af")
        ;; Choose the transaction set to summarise: a matching category /
        ;; sub-filter narrows it down; otherwise summarise the whole period.
        matched (cond
                  (and cat-name sub-name)
                  (when cat
                    (->> period-txns
                         (filter #(= (:category-id %) (:id cat)))
                         (filter #(category/match-fun (:description %) sub-name))))
                  cat-name
                  (when cat
                    (filter #(= (:category-id %) (:id cat)) period-txns))
                  :else
                  period-txns)
        total (when matched (reduce + 0 (map :amount matched)))]
    [:div.filter-path
     [:button.link {:on-click #(dispatch [:filter-path 0])} "All"]
     (when cat-name
       [:<>
        [:span.sep "›"]
        [:button.link.path-cat {:on-click #(dispatch [:filter-path 1])}
         [:span.cat-swatch {:style {:background-color cat-color}}]
         [:span cat-name]]])
     (when sub-name
       [:<>
        [:span.sep "›"]
        [:span.path-cat sub-name]])
     [:div {:style {:margin-left "auto" :display "flex" :align-items "center" :gap "12px"}}
      [multi-select-toggle]
      [transactions-search-input]
      (when matched
        [:span.fp-summary {:style {:margin-left 0}}
         (str (count matched) " transaksjoner · "
              (gstring/format "%.0f kr" total))])]]))


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
  (let [target (.-target elm)
        row (or (.closest target ".budget-row")
                (.closest target ".row"))]
    (some-> row .-attributes .-value .-value)))


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
  [:input.be-input.a-right
   {:type "text"
    :value (or (:target category) "")
    :placeholder "—"
    :on-change #(dispatch [:update-category-target (:id category) (-> % .-target .-value)])
    :on-blur #(dispatch [:save-category-target (:id category)])}])

(def ^:private bucket-order ["needs" "wants" "should" nil])
(def ^:private bucket-labels {"needs" "Behov" "wants" "Ønsker" "should" "Bør" nil "Ukategorisert"})

(defn- budget-bar
  "In-row progress bar. Track + fill width = spent/max(spent,target). When over
   target, an overbar with diagonal stripes spans the excess. A 1.5px vertical
   mark sits at the target position."
  [spent target color]
  (let [m (max spent target 1)
        spent-pct (* 100 (/ spent m))
        target-pct (* 100 (/ target m))
        over? (> spent target)
        excess-pct (- spent-pct target-pct)]
    [:div.budget-bar-wrap
     [:div.budget-bar-fill {:style {:width (str spent-pct "%")
                                    :background color
                                    :opacity (if over? 1 0.85)}}]
     (when over?
       [:div.budget-bar-overbar
        {:style {:left (str target-pct "%")
                 :width (str excess-pct "%")
                 :background (str "repeating-linear-gradient(45deg, " color
                                  " 0 4px, var(--c-down) 4px 8px)")}}])
     (when (pos? target)
       [:div.budget-bar-target-mark {:style {:left (str target-pct "%")}}])]))

(defn- expense-row [index category]
  (let [spent (Math/abs (or (:amount category) 0))
        tgt   (or (parse-target-num (:target category)) 0)
        diff  (- tgt spent)
        pct   (when (pos? tgt) (* 100 (/ spent tgt)))]
    [[:tr {:value (:id category) :key (:name category) :class "budget-row"}
      [:td [:a {:class "cursor-pointer sb-edit"
                :on-click #(dispatch [:edit-category3 (get-value-of-parent-row %) index])}
            "Endre"]]
      [:td.cat-stripe {:style {:--_cat-color (:color category)
                               :color (:color category)}}]
      [:td [:a {:class "cursor-pointer"
                :style {:color "inherit"}
                :on-click #(dispatch [:view-category (:name category)])}
            (:name category)]]
      [:td.a-right [target-input category]]
      [:td.a-right (fmt/format-amount spent)]
      [:td {:class (str "a-right " (if (>= diff 0) "pos" "neg"))}
       (if (pos? tgt)
         (str (if (>= diff 0) "+" "−") (fmt/format-amount (Math/abs diff)))
         [:span.dim "—"])]
      [:td.a-right.dim (when pct (gstring/format "%.0f%%" pct))]
      [:td.budget-bar-cell
       (when (pos? tgt) [budget-bar spent tgt (or (:color category) "#9ca3af")])]]]))

(defn- income-row [index category]
  [[:tr {:value (:id category) :key (:name category) :class "budget-row"}
    [:td [:a {:class "cursor-pointer sb-edit"
              :on-click #(dispatch [:edit-category3 (get-value-of-parent-row %) index])}
          "Endre"]]
    [:td.cat-stripe {:style {:--_cat-color (:color category)
                             :color (:color category)}}]
    [:td [:a {:class "cursor-pointer"
              :style {:color "inherit"}
              :on-click #(dispatch [:view-category (:name category)])}
          (:name category)]]
    [:td] [:td.a-right.pos (fmt/format-amount (or (:amount category) 0))]
    [:td] [:td] [:td]]])

(defn- edit-row [index category builder-category ready-to-store? show-diff? _max-abs-diff _used-colors]
  (let [spent (Math/abs (or (:amount category) 0))
        tgt   (or (parse-target-num (:target category)) 0)
        diff  (- tgt spent)
        pct   (when (pos? tgt) (* 100 (/ spent tgt)))]
    [;; The row itself looks just like a regular row — only the Endre button
     ;; flips to "Lukk" and we tag it with .is-editing for any pending styling.
     [:tr {:value (:id category) :key "edit-category-row"
           :class "budget-row is-editing"}
      [:td [:a {:class "cursor-pointer sb-edit"
                :on-click #(dispatch [:edit-category3 (get-value-of-parent-row %) index])}
            "Lukk"]]
      [:td.cat-stripe {:style {:--_cat-color (:color category)
                               :color (:color category)}}]
      [:td (:name category)]
      [:td.a-right
       (if show-diff?
         [target-input category]
         [:span.dim "—"])]
      [:td.a-right (fmt/format-amount spent)]
      [:td {:class (str "a-right " (if (>= diff 0) "pos" "neg"))}
       (when (and show-diff? (pos? tgt))
         (str (if (>= diff 0) "+" "−") (fmt/format-amount (Math/abs diff))))]
      [:td.a-right.dim (when (and show-diff? pct) (gstring/format "%.0f%%" pct))]
      [:td.budget-bar-cell
       (when (and show-diff? (pos? tgt))
         [budget-bar spent tgt (or (:color category) "#9ca3af")])]]
     ;; Edit panel — Mål, Bøtte, Rollover, Lagre / Slett.
     [:tr {:key (str (:id category) "-edit-panel") :class "budget-edit-panel"}
      [:td {:col-span 8}
       [:div.budget-edit
        [:div.be-block
         [:span.dim "Mål per måned"]
         [:input.be-input.a-right
          {:type "text"
           :value (or (:target category) "")
           :on-change #(dispatch [:update-category-target (:id category) (-> % .-target .-value)])
           :on-blur #(dispatch [:save-category-target (:id category)])}]
         [:span.dim "kr"]]
        [:div.be-block
         [:span.dim "Bøtte"]
         [:select.be-select
          {:value (or (:bucket builder-category) "")
           :on-change #(dispatch [:update-builder-category-bucket (-> % .-target .-value)])}
          [:option {:value ""} "— ingen —"]
          [:option {:value "needs"} "Behov"]
          [:option {:value "wants"} "Ønsker"]
          [:option {:value "should"} "Bør"]]]
        [:label.be-block {:style {:cursor "pointer"}}
         [:input {:type "checkbox"
                  :checked (boolean (:rollover? builder-category))
                  :on-change #(dispatch [:update-builder-category-rollover
                                         (-> % .-target .-checked)])}]
         [:span.dim "Rollover ubrukt"]]
        [:div {:style {:margin-left "auto" :display "flex" :gap "6px"}}
         [:button (-> {:class "btn-primary-xs"
                       :on-click #(dispatch [:store-category3])}
                      (add-disabled ready-to-store?))
          "Lagre"]
         [:button {:class "btn-ghost-xs"
                   :style {:color "var(--c-down)"}
                   :on-click #(when (js/confirm (str "Slett kategori \"" (:name category) "\"?"))
                                (dispatch [:delete-category (:id category)]))}
          "Slett"]]]]]]))

(def ^:private bucket-subtitles
  {"needs"  "Mat, bolig, transport — det som må betales"
   "wants"  "Aktiviteter, ting, ferie — for livskvalitet"
   "should" "Sparing, nedbetaling — for fremtiden"
   nil      "Mangler bøtte"})

(defn- bucket-header-row [bucket-key label cats]
  (let [spent  (->> cats (map #(Math/abs (or (:amount %) 0))) (reduce + 0))
        target (->> cats (map #(or (parse-target-num (:target %)) 0)) (reduce + 0))
        pct    (when (pos? target) (* 100 (/ spent target)))
        over?  (> spent target)
        sub    (get bucket-subtitles bucket-key)]
    [:tr {:key (str "bh-" (or bucket-key "none")) :class "bucket-header"}
     [:td {:col-span 8}
      [:div.bucket-head-flex
       [:span.bucket-name label]
       (when sub [:span.dim sub])
       [:div.bucket-meta
        (when pct [:span {:class (when over? "neg")} (gstring/format "%.0f%%" pct)])
        [:span.dim (str (fmt/format-amount spent) " / " (fmt/format-amount target))]]]]]))

(defn- income-header-row [total-income]
  [:tr {:key "bh-income" :class "bucket-header"}
   [:td {:col-span 8}
    [:div.bucket-head-flex
     [:span.bucket-name "Inntekt"]
     [:span.dim "Innkommende penger"]
     [:div.bucket-meta
      [:span.pos (fmt/format-amount total-income)]]]]])

(defn- grand-total-row [target spent diff]
  [:tr {:key "grand-total" :class "grand-total"}
   [:td]
   [:td]
   [:td "Resultat"]
   [:td.a-right (fmt/format-amount target)]
   [:td.a-right (fmt/format-amount spent)]
   [:td {:class (str "a-right " (if (>= diff 0) "pos" "neg"))}
    (str (if (>= diff 0) "+" "−") (fmt/format-amount (Math/abs diff)))]
   [:td] [:td]])

(def ^:private bucket-target-pct
  {"needs" 0.55 "wants" 0.30 "should" 0.15})

(defn- budget-summary-tiles [total-income total-spent total-target]
  (let [disponibelt (- total-income total-spent)
        igjen-budsjett (- total-target total-spent)
        budget-pct (when (pos? total-target)
                     (* 100 (/ (max 0 igjen-budsjett) total-target)))]
    [:div.budget-summary-grid
     [:div.budget-tile
      [:div.status-label "Inntekt"]
      [:div.big-mono.pos
       (fmt/format-amount total-income)
       [:span.kr-suffix " kr"]]
      [:div.status-sub "denne måned"]]
     [:div.budget-tile
      [:div.status-label "Brukt"]
      [:div.big-mono
       (fmt/format-amount total-spent)
       [:span.kr-suffix " kr"]]
      [:div.status-sub
       (str "av " (fmt/format-amount total-target) " budsjett")]]
     [:div.budget-tile
      [:div.status-label "Disponibelt"]
      [:div {:class (str "big-mono " (if (>= disponibelt 0) "pos" "neg"))}
       (str (if (>= disponibelt 0) "+" "−") (fmt/format-amount (Math/abs disponibelt)))
       [:span.kr-suffix " kr"]]
      [:div.status-sub "inntekt − utgifter"]]
     [:div.budget-tile
      [:div.status-label "Igjen i budsjett"]
      [:div {:class (str "big-mono " (if (>= igjen-budsjett 0) "pos" "neg"))}
       (str (if (>= igjen-budsjett 0) "+" "−") (fmt/format-amount (Math/abs igjen-budsjett)))
       [:span.kr-suffix " kr"]]
      [:div.status-sub
       (if budget-pct (str (fmt/format-amount budget-pct) "% av budsjett") "—")]]]))

(defn- budget-ribbon [bucket-totals bucket-targets total-income]
  [:div.ribbon
   (for [k ["needs" "wants" "should"]]
     (let [label (get bucket-labels k)
           spent (get bucket-totals k 0)
           target (get bucket-targets k 0)
           target-pct-of-income (* 100 (get bucket-target-pct k))
           pct-of-income (if (pos? total-income) (* 100 (/ spent total-income)) 0)
           fill-pct (if (pos? target) (min 100 (* 100 (/ spent target))) 0)
           over? (> pct-of-income target-pct-of-income)]
       ^{:key k}
       [:div.ribbon-cell
        [:div.ribbon-header
         [:span.ribbon-name label]
         [:span.ribbon-pct
          (gstring/format "%.0f%%" pct-of-income)
          [:span.dim (str " / " (gstring/format "%.0f%%" target-pct-of-income))]]]
        [:div.ribbon-bar
         [:div.ribbon-bar-fill
          {:style {:width (str fill-pct "%")
                   :background (if over? "var(--c-down)" "var(--c-up)")}}]]
        [:div.ribbon-foot
         [:span (fmt/format-amount spent)]
         [:span.dim (str "/ " (fmt/format-amount target))]]]))])

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
        used-colors (into #{} (keep :color all-categories))
        builder-category @(subscribe [:builder-category])
        edit-category? (fn [cat] (= (:id cat) (:id builder-category)))
        new-category? (= (:id builder-category) "new-id")
        ready-to-store? (category/ready-to-store? builder-category)

        buckets (group-by #(or (:bucket %) nil) expense-cats)
        income-sum  (->> income-cats (map #(or (:amount %) 0)) (reduce + 0))
        expense-sum (->> expense-cats (map #(Math/abs (or (:amount %) 0))) (reduce + 0))
        target-sum  (->> expense-cats (map #(or (parse-target-num (:target %)) 0)) (reduce + 0))
        result-diff (- income-sum expense-sum)
        bucket-totals  (into {} (for [k bucket-order]
                                  [k (->> (get buckets k []) (map #(Math/abs (or (:amount %) 0))) (reduce + 0))]))
        bucket-targets (into {} (for [k bucket-order]
                                  [k (->> (get buckets k []) (map #(or (parse-target-num (:target %)) 0)) (reduce + 0))]))

        income-rows (mapcat identity
                     (map-indexed
                      (fn [idx cat]
                        (if (edit-category? cat)
                          (edit-row idx cat builder-category ready-to-store? false nil used-colors)
                          (income-row idx cat)))
                      income-cats))

        bucket-rows
        (mapcat
         (fn [bucket-key]
           (let [cats (get buckets bucket-key)
                 label (get bucket-labels bucket-key)]
             (when (seq cats)
               (concat
                [(bucket-header-row bucket-key label cats)]
                (mapcat identity
                 (map-indexed
                  (fn [idx cat]
                    (if (edit-category? cat)
                      (edit-row idx cat builder-category ready-to-store? true nil used-colors)
                      (expense-row idx cat)))
                  cats))))))
         bucket-order)

        new-row (when new-category?
                  (edit-row 0 {:id "new-id"} builder-category ready-to-store? true nil used-colors))]
    [:div
     [budget-summary-tiles income-sum expense-sum target-sum]
     [budget-ribbon bucket-totals bucket-targets income-sum]
     [treemap/target-treemap {:height-ratio 0.32}]
     [:div.budget-table-wrap
      [:table.budget-table
       [:thead
        [:tr
         [:th {:style {:width "50px"}}]
         [:th {:style {:width "18px"}}]
         [:th "Kategori"]
         [:th.a-right "Mål"]
         [:th.a-right "Brukt"]
         [:th.a-right "Differanse"]
         [:th.a-right "%"]
         [:th "Progresjon"]]]
       [:tbody {:id "categories-tbody"}
        [income-header-row income-sum]
        income-rows
        bucket-rows
        (when new-row (mapcat identity [new-row]))
        [grand-total-row target-sum expense-sum result-diff]]]]]))

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

(defn- reauth-banner []
  (let [reauth @(subscribe [:bank-reauth])]
    (when reauth
      [:div {:style {:display "flex" :align-items "center" :gap "12px"
                     :padding "12px 16px" :margin-bottom "12px"
                     :background-color "#fff4e5" :border "1px solid #f0c38a"
                     :border-radius "6px" :color "#8a5a00"}}
       [:span {:style {:font-size "18px"}} "⚠️"]
       [:span {:style {:font-size "14px" :flex "1"}}
        "Bankforbindelsen har utløpt. Logg inn på nytt med BankID for å hente nye transaksjoner og saldo."]
       [:button {:style {:padding "6px 14px" :background-color "#8a5a00" :color "white"
                         :border "none" :border-radius "6px" :cursor "pointer"
                         :font-size "14px" :white-space "nowrap"}
                 :on-click #(dispatch [:reauth-account (:account-id reauth)])}
        "Koble til på nytt"]])))

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
    [segmented {:options [[:table        "Tabell"]
                          [:bar-chart    "Stolpediagram"]
                          [:summed-table "Summert"]]
                :selected display-option
                :on-select #(dispatch [:navigate [nil % nil]])}]))

(defn remove-barchart []
  (-> d3 (.selectAll "#mychart svg") (.remove)))

(defn displayed-transactions-viewer
  "Renders only the chosen content view (table / chart / summed) — without the
   right-hand categories sidebar. The sidebar is rendered separately by the page
   so it can sit in its own .sidebar-panel column."
  []
  (remove-barchart)
  (let [displayed-transactions-data @(subscribe [:displayed-transactions-data])
        cats @(subscribe [:categories])
        period @(subscribe [:period])
        display-option (:display-option displayed-transactions-data)
        displayed-transactions (:displayed-transactions displayed-transactions-data)
        chart-size (:chart-size displayed-transactions-data)]
    (case display-option
      :table (t-table/transactions-table-main displayed-transactions-data cats)
      :bar-chart (chart/stacked-barchart displayed-transactions cats period chart-size)
      :summed-table [summed-table/summed-table displayed-transactions-data cats]
      [:div [:p (str "Unknown display option: " (pr-str display-option))]])))

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
  [[:transaksjoner "Transaksjoner" :list]
   [:budsjett      "Budsjett"      :pie]
   [:dagligvarer   "Dagligvarer"   :cart]
   [:laan          "Lån"           :trend-up]
   [:kontoer       "Kontoer"       :wallet]
   [:formue        "Formue"        :trend-up]
   [:rapporter     "Rapporter"     :bars]])

(defn- nav-sidebar []
  (let [active @(subscribe [:active-menu])
        theme  @(subscribe [:theme])]
    [:nav.nav-sidebar
     [:div.brand
      [:span.brand-mark "◆"]
      [:span.brand-name "odin"]]
     [:ul.nav-list
      (doall
       (for [[k label icon-name] menu-items]
         ^{:key k}
         [:li
          [:button {:class (str "nav-item" (when (= k active) " is-active"))
                    :on-click #(dispatch [:set-active-menu k])}
           [icon icon-name {:size 16}]
           [:span label]]]))]
     [:div.nav-foot
      [:button {:class "nav-item"
                :on-click #(dispatch [:set-theme (if (= theme :dark) :light :dark)])
                :title (if (= theme :dark) "Lyst tema" "Mørkt tema")}
       [icon (if (= theme :dark) :sun :moon) {:size 16}]
       [:span (if (= theme :dark) "Lyst tema" "Mørkt tema")]]
      [user-menu]]]))

(defn- transactions-search-input []
  (let [q @(subscribe [:transactions-search])]
    [:div.search-wrap
     [icon :search {:size 14}]
     [:input {:type "text"
              :value q
              :placeholder "Søk i transaksjoner…"
              :on-change #(dispatch [:set-transactions-search (-> % .-target .-value)])}]]))

(defn- multi-select-toggle []
  (let [active? (some? @(subscribe [:multi-select]))]
    [:button {:class (str "icon-btn" (when active? " is-active"))
              :title "Velg flere"
              :on-click #(dispatch [:toggle-multi-select-mode])}
     [icon :check {:size 14}]]))

(defn- transaksjoner-content []
  (let [cats @(subscribe [:categories])
        display-option (-> @(subscribe [:displayed-transactions-data]) :display-option)
        show-sidebar? (not= display-option :bar-chart)]
    [:div
     [loading-banner]
     [metrics-bar]
     [treemap/category-treemap {:height-ratio 0.25}]
     [:div.controls-row
      [account-selector]
      [displayed-transactions-view-selector]
      [period-sel-v2/period-selector]]
     [filter-path]
     (if show-sidebar?
       [:div.split-content
        [:div.content-main
         [displayed-transactions-viewer]]
        [:div.sidebar-panel
         [t-table/transactions-sidebar cats]]]
       [:div.content-main
        [displayed-transactions-viewer]])]))

(defn- budsjett-content []
  [:div
   [:div.page-header
    [:h2 "Budsjett"]
    [:div.right [period-sel-v2/period-selector]]]
   [categories]])

(defn- format-grocery-date
  "Epoch ms → YYYY-MM-DD."
  [ms]
  (when ms
    (let [d (js/Date. ms)]
      (when-not (js/isNaN (.getTime d))
        (let [pad (fn [n] (gstring/format "%02d" n))]
          (str (.getFullYear d) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d))))))))

(defn- dagligvarer-content []
  (let [items @(subscribe [:grocery-items])
        accounts @(subscribe [:accounts])
        grocery-accs (filter grocery-account? accounts)
        sync-state @(subscribe [:grocery-sync])
        syncing? (= :loading (:status sync-state))]
    [:div
     [:div.page-header
      [:h2 "Dagligvarer"]
      [:div.right
       [pill-button {:variant :primary
                     :disabled (or syncing? (empty? grocery-accs))
                     :on-click #(dispatch [:sync-grocery nil])}
        (if syncing? "Synker…" "Oppdater kvitteringer")]]]

     (when (empty? grocery-accs)
       [:p {:style {:color "var(--text-dim)" :margin "16px 0"}}
        "Ingen dagligvarekontoer koblet til. Gå til "
        [:button.link {:on-click #(dispatch [:set-active-menu :kontoer])} "Kontoer"]
        " og legg til Trumf, Rema 1000 eller Coop."])

     (when-let [st (:status sync-state)]
       (case st
         :success
         (let [r (:result sync-state)]
           [:p.dim.small {:style {:margin "8px 0"}}
            (str "Synket: " (:imported r 0) " nye, " (:updated r 0) " oppdaterte"
                 (when (seq (:errors r))
                   (str " · " (count (:errors r)) " feil")))])
         :error
         [:p.small {:style {:margin "8px 0" :color "var(--c-down)"}}
          (or (:error sync-state) "Synk feilet")]
         nil))

     (if (empty? items)
       [:p {:style {:color "var(--text-dim)" :margin "20px 0"}}
        "Ingen varer ennå. Koble til en app under Kontoer og trykk «Oppdater kvitteringer»."]
       [:table.txn-table
        [:thead
         [:tr
          [:th "Vare"]
          [:th "Dato"]
          [:th "Butikk"]
          [:th.a-right "Beløp"]]]
        [:tbody
         (for [[idx item] (map-indexed vector items)]
           ^{:key (or (:source-id item) (str (:date item) "-" idx))}
           [:tr.txn-row
            [:td (:name item)]
            [:td.dim.mono.small (or (format-grocery-date (:date item)) "—")]
            [:td.dim.small (or (:store item) (provider-label (:provider item)) "—")]
            [:td.a-right.mono
             (if-let [a (:amount item)] (format-kr a) "—")]])]])]))

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
    "nordnet" "Nordnet"
    "trumf" "Trumf"
    "rema" "Rema 1000"
    "coop" "Coop"
    provider-key))

(def ^:private grocery-providers #{"trumf" "rema" "coop"})

(defn- grocery-account? [account]
  (contains? grocery-providers (:provider account)))

(defn account-selector
  "Dropdown on the transactions page to pick which account's transactions to show."
  []
  (let [accounts (remove grocery-account? @(subscribe [:accounts]))
        selected-id @(subscribe [:selected-account-id])]
    (when (seq accounts)
      [:select.account-selector
       {:value (or selected-id "")
        :on-change #(dispatch [:select-account (-> % .-target .-value)])}
       (for [account accounts]
         ^{:key (:account-id account)}
         [:option {:value (:account-id account)}
          (let [name (:account-name account)]
            (if (seq name)
              name
              (provider-label (:provider account))))])])))

(defn- read-csv-file!
  "Read a Nordnet CSV file (UTF-16 LE) as text and dispatch the import."
  [account-id file]
  (when file
    (let [reader (js/FileReader.)]
      (set! (.-onload reader)
            (fn [e] (dispatch [:import-nordnet-csv account-id (.. e -target -result)])))
      (.readAsText reader file "utf-16le"))))

(defn- read-credit-csv-file!
  "Read a Nordnet credit-account (kredittkonto) CSV and dispatch the credit import."
  [account-id file]
  (when file
    (let [reader (js/FileReader.)]
      (set! (.-onload reader)
            (fn [e] (dispatch [:import-nordnet-credit-csv account-id (.. e -target -result)])))
      (.readAsText reader file "utf-16le"))))

(defn- build-callback-uri []
  (let [base api/*base-url*]
    (if (s/starts-with? base "http")
      ;; Absolute URL (local dev): e.g. http://localhost:8080 -> http://localhost:8080/auth/bank/callback
      (str base "/auth/bank/callback")
      ;; Relative URL (production): e.g. /api -> https://domain/api/auth/bank/callback
      (str (.-origin js/window.location) base "/auth/bank/callback"))))

(defn- copy-to-clipboard [text]
  (.writeText js/navigator.clipboard text))

(defn- format-account-number
  "Norwegian bank account number formatting: 4-2-5 with NBSP separators.
   E.g. '12345678901' → '1234 56 78901'. Non-11-digit values pass through."
  [s]
  (let [digits (when s (s/replace s #"\D" ""))]
    (if (and digits (= 11 (count digits)))
      (str (subs digits 0 4) " " (subs digits 4 6) " " (subs digits 6 11))
      s)))

(defn- format-last-sync
  "Render an ISO instant as 'YYYY-MM-DD HH:MM'. Falls back to '—' on parse fail."
  [iso]
  (let [d (when iso (js/Date. iso))]
    (if (and d (not (js/isNaN (.getTime d))))
      (let [pad (fn [n] (gstring/format "%02d" n))]
        (str (.getFullYear d) "-" (pad (inc (.getMonth d))) "-" (pad (.getDate d))
             " " (pad (.getHours d)) ":" (pad (.getMinutes d))))
      "—")))

(defn- kontoer-content []
  (let [callback-uri  (build-callback-uri)
        provider      (r/atom "sparebank1-ost")
        account-name  (r/atom "")
        client-id     (r/atom "")
        client-secret (r/atom "")
        show-form?    (r/atom false)
        copied?       (r/atom false)
        confirm-delete (r/atom nil)
        coop-callback (r/atom "")
        reset-form!   (fn []
                        (reset! show-form? false)
                        (reset! provider "sparebank1-ost")
                        (reset! account-name "")
                        (reset! client-id "")
                        (reset! client-secret "")
                        (reset! coop-callback ""))]
    (fn []
      (let [accounts @(subscribe [:accounts])
            import-state @(subscribe [:nordnet-import])
            grocery-sync @(subscribe [:grocery-sync])
            total    (reduce + 0 (keep :balance accounts))]
        [:div
         ;; Header
         [:div.page-header
          [:h2 "Kontoer"]
          [:div.right
           (when-not @show-form?
             [pill-button {:variant :primary
                           :on-click #(reset! show-form? true)}
              "+ Legg til konto"])]]

         ;; Summary card
         [:div.account-summary
          [:div.status-label "TOTAL SALDO OVER ALLE KONTOER"]
          [:div.big-mono (if (pos? (count (keep :balance accounts)))
                           (format-kr total)
                           "—")]
          [:div.status-sub
           (str (count accounts) (if (= 1 (count accounts)) " konto" " kontoer"))]]

         ;; Accounts table or empty state
         (if (empty? accounts)
           [:p {:style {:color "var(--text-dim)" :margin "20px 0"}}
            "Ingen kontoer koblet til ennå."]
           [:table.txn-table.accounts-table
            [:thead
             [:tr
              [:th "Konto"]
              [:th "Tilbyder"]
              [:th "Kontonummer"]
              [:th.a-right "Saldo"]
              [:th "Sist sync"]
              [:th]]]
            [:tbody
             (for [account accounts]
               ^{:key (:account-id account)}
               [:tr.txn-row.account-row
                [:td
                 [:div.account-name-cell
                  [icon (if (grocery-account? account) :cart :wallet) {:size 14}]
                  [:span (or (:account-name account) "—")]]]
                [:td.txn-desc.dim (provider-label (:provider account))]
                [:td.acc-number.mono.dim
                 (or (format-account-number (:account-number account)) "—")]
                [:td.a-right.acc-balance.mono
                 (if-let [b (:balance account)] (format-kr b) "—")]
                [:td.acc-sync.dim.mono.small
                 (if (:last-sync account) (format-last-sync (:last-sync account)) "—")]
                [:td.a-right
                 (when (= "nordnet" (:provider account))
                   (let [acc-id (:account-id account)
                         loading? (and (= :loading (:status import-state))
                                       (= acc-id (:account-id import-state)))]
                     [:span
                      [:label.link {:style {:margin-right "12px" :cursor "pointer"}}
                       (if loading? "Importerer…" "Importer CSV")
                       [:input {:type "file"
                                :accept ".csv"
                                :style {:display "none"}
                                :disabled loading?
                                :on-change (fn [e]
                                             (let [file (-> e .-target .-files (aget 0))]
                                               (read-csv-file! acc-id file)
                                               (set! (-> e .-target .-value) "")))}]]
                      [:label.link {:style {:margin-right "12px" :cursor "pointer"}}
                       "Importer kreditt-CSV"
                       [:input {:type "file"
                                :accept ".csv"
                                :style {:display "none"}
                                :disabled loading?
                                :on-change (fn [e]
                                             (let [file (-> e .-target .-files (aget 0))]
                                               (read-credit-csv-file! acc-id file)
                                               (set! (-> e .-target .-value) "")))}]]]))
                 (when (grocery-account? account)
                   (let [acc-id (:account-id account)
                         loading? (and (= :loading (:status grocery-sync))
                                       (= acc-id (:account-id grocery-sync)))]
                     [:span
                      (when (and (= "coop" (:provider account))
                                 (not (:has-credentials account)))
                        [:button.link {:style {:margin-right "12px"}
                                       :on-click #(do (reset! show-form? true)
                                                      (reset! provider "coop")
                                                      (dispatch [:start-coop-login acc-id nil]))}
                         "Logg inn med Coop"])
                      [:button.link {:style {:margin-right "12px"}
                                     :disabled loading?
                                     :on-click #(dispatch [:sync-grocery acc-id])}
                       (if loading? "Synker…" "Synk kvitteringer")]]))
                 [:button.link.neg-link
                  {:on-click #(reset! confirm-delete (:account-id account))}
                  "Fjern"]]])]])

         ;; Import result banner
         (when-let [st (:status import-state)]
           (case st
             :success (let [r (:result import-state)]
                        [:p.dim.small {:style {:margin "8px 0"}}
                         (str "Importert " (:imported r) " nye transaksjoner"
                              (when (pos? (:updated r 0))
                                (str ", oppdaterte " (:updated r))) ".")])
             :error [:p.small {:style {:margin "8px 0" :color "var(--c-down)"}}
                     "Import feilet. Sjekk at filen er en gyldig Nordnet-eksport."]
             nil))

         ;; Add-account form panel
         (when @show-form?
           [:div.add-account-form
            [:div.form-head
             [:h3 (cond
                    (= "nordnet" @provider) "Legg til Nordnet"
                    (contains? grocery-providers @provider) (str "Legg til " (provider-label @provider))
                    :else "Koble til Sparebank1 Østlandet")]
             [:button.icon-btn {:on-click reset-form!}
              [icon :close {:size 14}]]]

            ;; Provider selector
            [:div.form-field
             [:label "Tilbyder"]
             [:select.form-input
              {:value @provider
               :on-change #(reset! provider (.. % -target -value))}
              [:option {:value "sparebank1-ost"} "Sparebank1 Østlandet"]
              [:option {:value "nordnet"} "Nordnet"]
              [:option {:value "trumf"} "Trumf (Kiwi, Meny, Spar, …)"]
              [:option {:value "rema"} "Rema 1000 (Æ)"]
              [:option {:value "coop"} "Coop Medlem"]]]

            (cond
              (= "nordnet" @provider)
              ;; Nordnet: CSV-only, no OAuth
              [:div
               [:p.form-intro
                "Nordnet har ingen åpen API. Legg til kontoen her, og last deretter opp en transaksjons-CSV eksportert fra Nordnet."]
               [:div.form-field
                [:label "Kontonavn"]
                [:input.form-input
                 {:type "text"
                  :value @account-name
                  :on-change #(reset! account-name (.. % -target -value))
                  :placeholder "f.eks. Nordnet aksjesparekonto"}]]
               [:div.form-actions
                [pill-button {:variant :primary
                              :on-click #(do
                                           (dispatch [:connect-account "nordnet"
                                                      (when (seq @account-name) @account-name)
                                                      nil nil nil])
                                           (reset-form!))}
                 "Legg til"]
                [pill-button {:variant :ghost :on-click reset-form!} "Avbryt"]]]

              (= "coop" @provider)
              (let [coop-login @(subscribe [:coop-login])
                    awaiting? (contains? #{:awaiting :loading :error} (:status coop-login))]
                [:div
                 [:p.form-intro
                  "Logger inn via Coop Min Side (BankID). Etter innlogging får du et tilgangstoken mot "
                  [:code "api.coop.no"] " — samme API som Coop-appen bruker til kvitteringer."]
                 [:div.form-field
                  [:label "Navn (valgfritt)"]
                  [:input.form-input
                   {:type "text"
                    :value @account-name
                    :on-change #(reset! account-name (.. % -target -value))
                    :placeholder "f.eks. Coop"}]]
                 (when-not awaiting?
                   [:div.form-actions
                    [pill-button {:variant :primary
                                  :on-click #(dispatch [:start-coop-login nil
                                                        (when (seq @account-name) @account-name)])}
                     "Logg inn med Coop"]
                    [pill-button {:variant :ghost :on-click reset-form!} "Avbryt"]])
                 (when awaiting?
                   [:div
                    (let [block-cmd (str "printf '%s\\n' '127.0.0.1 minside.coop.no # odin-coop-block' '::1 minside.coop.no # odin-coop-block' | sudo tee -a /etc/hosts >/dev/null && sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder")
                          unblock-cmd "sudo sed -i '' '/odin-coop-block/d' /etc/hosts && sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder"]
                      [:p.dim.small {:style {:line-height "1.45" :margin "8px 0 12px"}}
                       "Chrome blokkerer ikke denne redirecten. Callback er en "
                       [:strong "full side-navigasjon"] ", ikke XHR — derfor laster Min Side likevel og koden blir brukt."
                       [:br] [:br]
                       [:strong "Blokker domenet i stedet (Terminal på Mac):"]
                       [:br]
                       "1. Kjør dette, skriv Mac-passordet, og " [:strong "lukk Coop-fanen"] ":"
                       [:pre.mono {:style {:white-space "pre-wrap" :font-size "11px" :margin "8px 0"}}
                        block-cmd]
                       [:button.link {:style {:margin-bottom "8px"}
                                      :on-click #(copy-to-clipboard block-cmd)}
                        "Kopier blokker-kommando"]
                       [:br]
                       "2. Klikk «Åpne Coop-innlogging på nytt» og fullfør BankID."
                       [:br]
                       "3. Nettleseren skal si at siden ikke kan nås. "
                       "Adresselinjen blir stående på "
                       [:code "minside.coop.no/api/auth/callback/auth0/?code=..."]
                       " — kopier " [:strong "hele"] " den URLen hit."
                       [:br]
                       "4. Når token er hentet, fjern blokkeringen:"
                       [:pre.mono {:style {:white-space "pre-wrap" :font-size "11px" :margin "8px 0"}}
                        unblock-cmd]
                       [:button.link {:on-click #(copy-to-clipboard unblock-cmd)}
                        "Kopier opphev-kommando"]])
                    (when (:oauth-url coop-login)
                      [:p.small
                       [:button.link {:on-click #(.open js/window (:oauth-url coop-login) "_blank")}
                        "Åpne Coop-innlogging på nytt"]])
                    [:div.form-field
                     [:label "Callback-URL fra adresselinjen"]
                     [:input.form-input
                      {:type "text"
                       :value @coop-callback
                       :placeholder "https://minside.coop.no/api/auth/callback/auth0/?code=..."
                       :on-change #(reset! coop-callback (.. % -target -value))}]]
                    (when (= :error (:status coop-login))
                      [:p.small {:style {:color "var(--c-down)"}}
                       (or (:error coop-login) "Innlogging feilet")])
                    [:div.form-actions
                     [pill-button {:variant :primary
                                   :disabled (or (empty? @coop-callback)
                                                 (= :loading (:status coop-login))
                                                 (nil? (:account-id coop-login)))
                                   :on-click #(dispatch [:complete-coop-login
                                                         (:account-id coop-login)
                                                         @coop-callback])}
                      (if (= :loading (:status coop-login)) "Henter token…" "Fullfør og hent token")]
                     [pill-button {:variant :ghost :on-click reset-form!} "Avbryt"]]])])

              (contains? grocery-providers @provider)
              ;; Trumf / Rema: store access token
              [:div
               [:p.form-intro
                "Kobler til via appens private API (uoffisiell). Lim inn et gyldig tilgangstoken (Authorization Bearer). Tokenet lagres som kontolegitimasjon på serveren."]
               [:div.form-field
                [:label "Navn (valgfritt)"]
                [:input.form-input
                 {:type "text"
                  :value @account-name
                  :on-change #(reset! account-name (.. % -target -value))
                  :placeholder (str "f.eks. " (provider-label @provider))}]]
               [:div.form-field
                [:label "Brukernavn / telefon (valgfritt)"]
                [:input.form-input
                 {:type "text"
                  :value @client-id
                  :on-change #(reset! client-id (.. % -target -value))
                  :placeholder "f.eks. 41234567"}]]
               [:div.form-field
                [:label "Tilgangstoken"]
                [:input.form-input
                 {:type "password"
                  :value @client-secret
                  :on-change #(reset! client-secret (.. % -target -value))
                  :placeholder "Bearer-token"}]]
               [:div.dim.small {:style {:margin "8px 0 12px" :line-height "1.45"}}
                (case @provider
                  "trumf"
                  [:span "Logg inn på trumf.no → DevTools (F12) → Network. Filtrer på "
                   [:code "ngdata"] " eller " [:code "trumf"]
                   ". Åpne et XHR/fetch-kall → Headers → " [:code "Authorization: Bearer …"]
                   " og lim inn tokenet (med eller uten «Bearer »)."]
                  "rema"
                  [:span "Prøv aeg.no / Æ i nettleser, eller intercept Æ-app-trafikk. Se etter kall til "
                   [:code "api.rema.no"] " med " [:code "Authorization"]
                   " og " [:code "ocp-apim-subscription-key"] "."]
                  [:span "Lim inn Authorization Bearer-token fra app/nettleser."])]
               [:div.form-actions
                [pill-button {:variant :primary
                              :disabled (empty? @client-secret)
                              :on-click #(when (seq @client-secret)
                                           (dispatch [:connect-account @provider
                                                      (when (seq @account-name) @account-name)
                                                      (when (seq @client-id) @client-id)
                                                      @client-secret
                                                      nil])
                                           (reset-form!))}
                 "Legg til"]
                [pill-button {:variant :ghost :on-click reset-form!} "Avbryt"]]]

              :else
              ;; Sparebank1: OAuth client credentials
              [:div
               [:p.form-intro
                "For å koble til en bankkonto trenger du en utviklerklient fra Sparebank1."]
               [:ol.form-steps
                [:li "Gå til "
                 [:a.link-ext {:href "https://developer.sparebank1.no" :target "_blank"}
                  "developer.sparebank1.no"]
                 " og klikk \"Log in\" for å opprette en utviklerkonto."]
                [:li "Følg \"Getting started\"-guiden og opprett en ny klient."]
                [:li "Gi klienten et valgfritt navn og lim inn callback-URLen nedenfor."]
                [:li "Kopier klientens Client ID og Client Secret og lim inn her."]]
               [:div.form-field
                [:label "Callback URI"]
                [:div.copy-row
                 [:code.mono callback-uri]
                 [pill-button {:variant :ghost-xs
                               :on-click #(do (copy-to-clipboard callback-uri)
                                              (reset! copied? true)
                                              (js/setTimeout (fn [] (reset! copied? false)) 2000))}
                  (if @copied? "Kopiert!" "Kopier")]]]
               [:div.form-field
                [:label "Client ID"]
                [:input.form-input
                 {:type "text"
                  :value @client-id
                  :on-change #(reset! client-id (.. % -target -value))
                  :placeholder "f.eks. 516d21d1-39f1-4712-978c-..."}]]
               [:div.form-field
                [:label "Client Secret"]
                [:input.form-input
                 {:type "password"
                  :value @client-secret
                  :on-change #(reset! client-secret (.. % -target -value))
                  :placeholder "f.eks. c6306ed8-08c9-4de3-..."}]]
               [:div.form-actions
                [pill-button {:variant :primary
                              :disabled (or (empty? @client-id) (empty? @client-secret))
                              :on-click #(when (and (seq @client-id) (seq @client-secret))
                                           (dispatch [:connect-account "sparebank1-ost" nil
                                                      @client-id @client-secret callback-uri])
                                           (reset-form!))}
                 "Koble til"]
                [pill-button {:variant :ghost :on-click reset-form!} "Avbryt"]]])])

         ;; Delete confirmation modal
         (when @confirm-delete
           [:div.modal-overlay {:on-click #(reset! confirm-delete nil)}
            [:div.modal {:on-click #(.stopPropagation %)}
             [:p "Er du sikker på at du vil fjerne denne kontoen?"]
             [:p.dim.small "All transaksjonshistorikk vil bli slettet."]
             [:div.modal-actions
              [pill-button {:variant :danger
                            :on-click #(do (dispatch [:delete-account @confirm-delete])
                                           (reset! confirm-delete nil))}
               "Fjern"]
              [pill-button {:variant :ghost
                            :on-click #(reset! confirm-delete nil)}
               "Avbryt"]]]])]))))

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
        deleting? (r/atom false)
        wrap (atom nil)
        on-doc-down (fn [e]
                      (when (and @open? @wrap
                                 (not (.contains @wrap (.-target e))))
                        (reset! open? false)
                        (reset! confirm-delete? false)))]
    (r/create-class
     {:display-name "user-menu"
      :component-did-mount
      (fn [] (.addEventListener js/document "mousedown" on-doc-down))
      :component-will-unmount
      (fn [] (.removeEventListener js/document "mousedown" on-doc-down))
      :reagent-render
      (fn []
        (let [auth @(subscribe [:auth])
              user (:user auth)
              email (or (:email user) "")]
          [:<>
           (when @deleting?
             [deleting-overlay])
           (when user
             [:div {:class (str "user-menu" (when @open? " is-open"))
                    :ref (fn [el] (reset! wrap el))}
              [:button.user-block
               {:type "button"
                :on-click #(swap! open? not)
                :title "Konto"}
               [:div.user-avatar (-> email (subs 0 1) (.toUpperCase))]
               [:div.user-info
                [:div.user-email email]
                [:div.user-status [:span.live-dot] "Synkronisert"]]
               [:span.user-menu-caret
                [icon :caret-down {:size 14}]]]
              (when @open?
                [:div.user-menu-dropdown
                 [:button.user-menu-item
                  {:type "button"
                   :on-click #(do (reset! open? false)
                                  (dispatch [:logout]))}
                  "Logg ut"]
                 (if @confirm-delete?
                   [:div.user-menu-confirm
                    [:p "Er du sikker? Alt av data vil bli slettet permanent."]
                    [:div.user-menu-confirm-actions
                     [pill-button {:variant :danger
                                   :on-click #(do (reset! deleting? true)
                                                  (reset! open? false)
                                                  (dispatch [:delete-user]))}
                      "Slett"]
                     [pill-button {:variant :ghost-xs
                                   :on-click #(reset! confirm-delete? false)}
                      "Avbryt"]]]
                   [:button.user-menu-item.is-danger
                    {:type "button"
                     :on-click #(reset! confirm-delete? true)}
                    "Slett konto"])])])]))})))

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

(def ^:private seg-colors
  {:paid-principal "var(--loan-paid-p)"
   :paid-interest  "var(--loan-paid-i)"
   :paid-fees      "var(--loan-paid-f)"
   :rem-principal  "var(--loan-rem-p)"
   :rem-interest   "var(--loan-rem-i)"
   :rem-fees       "var(--loan-rem-f)"})

(defn- loan-bar
  "Horizontal bar with 6 paid/remaining principal/interest/fee segments.
   When :max-total is given (cross-loan compare), the bar itself sizes to
   total/max-total of its grid column; segments fill 100% of the bar. The
   bar's overflow:hidden + border-radius then clips a rounded shape that
   matches the actual end of the visible segments."
  [summary & {:keys [max-total full?]}]
  (let [{:keys [remaining-principal remaining-interest remaining-fees
                paid-principal paid-interest paid-fees]} summary
        total (loan-total-cost summary)
        bar-width-pct (if (and max-total (pos? max-total))
                        (str (* 100.0 (/ total max-total)) "%")
                        "100%")
        ref (if (and max-total (pos? max-total)) max-total total)
        seg (fn [value k]
              (let [pct     (if (pos? total) (* 100.0 (/ value total)) 0)
                    abs-pct (if (pos? ref) (* 100.0 (/ value ref)) 0)
                    color   (get seg-colors k)]
                [:div.seg {:style {:width (str pct "%") :background color}
                           :title (format-kr value)}
                 (when (> abs-pct 8)
                   [:span.seg-label (format-kr value)])]))]
    [:div.loan-bar {:class (when full? "full")
                    :style {:width bar-width-pct}}
     (when (and paid-principal (pos? paid-principal))
       (seg paid-principal :paid-principal))
     (when (and paid-interest (pos? paid-interest))
       (seg paid-interest :paid-interest))
     (when (and paid-fees (pos? paid-fees))
       (seg paid-fees :paid-fees))
     (when (pos? remaining-principal)
       (seg remaining-principal :rem-principal))
     (when (pos? remaining-interest)
       (seg remaining-interest :rem-interest))
     (when (and remaining-fees (pos? remaining-fees))
       (seg remaining-fees :rem-fees))]))

(defn- loan-bar-legend []
  [:div.loan-legend
   (for [[k label] [[:paid-principal "Betalt avdrag"]
                    [:paid-interest "Betalt rente"]
                    [:paid-fees "Betalte gebyr"]
                    [:rem-principal "Gjenstående avdrag"]
                    [:rem-interest "Gjenstående rente"]
                    [:rem-fees "Gjenstående gebyr"]]]
     ^{:key k}
     [:div.legend-item
      [:div.legend-sw {:style {:background (get seg-colors k)}}]
      [:span label]])])

(defn- format-duration [months]
  (let [y (quot months 12)
        m (rem months 12)]
    (cond
      (zero? y) (str m " mnd")
      (zero? m) (str y " år")
      :else (str y " år " m " mnd"))))

(defn- weighted-avg-rate [loans]
  (let [total-bal (reduce + (keep #(:balance %) loans))]
    (if (pos? total-bal)
      (/ (reduce + (map #(* (or (:nominal-rate %) 0) (or (:balance %) 0)) loans))
         total-bal)
      0)))

(defn- loan-summary-tiles [summaries]
  (let [loans     (map first summaries)
        sums      (map second summaries)
        total-debt    (reduce + (map :remaining-principal sums))
        total-payment (reduce + (map :monthly-payment loans))
        total-paid    (reduce + (map (fn [s] (+ (or (:paid-principal s) 0)
                                                (or (:paid-interest s) 0)
                                                (or (:paid-fees s) 0)))
                                     sums))
        avg-rate (weighted-avg-rate loans)
        n-active (count loans)]
    [:div.loan-summary-grid
     [:div.budget-tile
      [:div.status-label "TOTAL GJELD"]
      [:div.big-mono.neg (format-kr total-debt)]
      [:div.status-sub (str n-active " aktive lån")]]
     [:div.budget-tile
      [:div.status-label "MÅNEDLIG BETALING"]
      [:div.big-mono (format-kr total-payment)]
      [:div.status-sub "samlet termin"]]
     [:div.budget-tile
      [:div.status-label "BETALT TOTALT"]
      [:div.big-mono.pos (format-kr total-paid)]
      [:div.status-sub "avdrag + rente + gebyr"]]
     [:div.budget-tile
      [:div.status-label "SNITTRENTE"]
      [:div.big-mono (str (gstring/format "%.2f" (* 100 avg-rate)) "%")]
      [:div.status-sub "veid på gjeld"]]]))

(defn- loan-comparison-panel [summaries max-total expanded-id]
  [:div.loan-comparison
   [:div.block-title "Sammenligning"]
   [loan-bar-legend]
   [:div.loan-bars
    (for [[loan summary] summaries]
      ^{:key (:id loan)}
      [:div.loan-bar-row.clickable
       {:on-click (fn []
                    (reset! expanded-id (:id loan))
                    (js/setTimeout
                     #(when-let [el (.getElementById js/document
                                      (str "loan-card-" (:id loan)))]
                        (.scrollIntoView el #js {:behavior "smooth" :block "start"}))
                     50))}
       [:div.loan-bar-label (:name loan)]
       [loan-bar summary :max-total max-total]])]])

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
                                           "Avdrag: " (format-kr principal) "<br>"
                                           "Rente: " (format-kr interest)
                                           (when (pos? fee)
                                             (str "<br>Gebyr: " (format-kr fee)))))
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

(defn- simulated-loan
  "Build a simulated loan map from the original loan and the user's sim ratom contents.
   Drives the Plan-tab chart and schedule table."
  [loan sim]
  (let [rate-pct (parse-num-or (:rate-pct sim) (* 100 (:nominal-rate loan)))
        payment  (parse-num-or (:monthly-payment sim) (:monthly-payment loan))
        lump     (max 0 (parse-num-or (:lump-sum sim) 0))
        balance  (max 0 (- (:balance loan) lump))]
    (-> loan
        (assoc :nominal-rate (/ rate-pct 100.0))
        (assoc :monthly-payment payment)
        (assoc :balance balance)
        (dissoc :original-amount))))

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

;; ---------- Loan card: tabs ----------

(defn- stripe-style
  "Returns inline style for the .info-island ::before color stripe.
   colors is a vector of CSS color strings. Empty → transparent (no stripe).
   Single → flat color. Multiple → vertical hard-stop linear-gradient."
  [colors]
  (let [n (count colors)]
    (cond
      (zero? n) {}
      (= 1 n)   {"--_stripe" (first colors)}
      :else
      (let [step (/ 100.0 n)
            stops (->> colors
                       (map-indexed
                        (fn [i c]
                          (str c " " (gstring/format "%.4f" (* i step)) "%, "
                               c " " (gstring/format "%.4f" (* (inc i) step)) "%")))
                       (s/join ", "))]
        {"--_stripe" (str "linear-gradient(to bottom, " stops ")")}))))

(defn- info-island
  "Info island with left color stripe + optional simulator sub-line.
   props: :label :value :colors :sim-value :delta-text :delta-class"
  [{:keys [label value colors sim-value delta-text delta-class]}]
  [:div.info-island {:style (stripe-style colors)}
   [:div.info-label label]
   [:div.info-value value]
   (when sim-value
     [:div.info-sim
      [:span.info-arrow "→"]
      [:span.info-sim-value sim-value]
      (when delta-text
        [:span.info-delta {:class (when delta-class (name delta-class))} delta-text])])])

(defn- delta-fields
  "Returns {:sim-value :delta-text :delta-class} for an info-island sub-line.
   Returns nil when cur = sim. When colored? is false, no delta-class is set."
  [cur sim formatter & {:keys [value-fn colored?]
                        :or   {value-fn formatter colored? true}}]
  (when (not= cur sim)
    (let [d (- sim cur)
          abs-d (Math/abs d)
          sign (cond (zero? d) "±" (neg? d) "- " :else "+ ")
          base {:sim-value  (value-fn sim)
                :delta-text (str sign (formatter abs-d))}]
      (if colored?
        (assoc base :delta-class (cond (zero? d) nil (neg? d) :pos :else :neg))
        base))))

(def ^:private month-names-no
  ["jan" "feb" "mar" "apr" "mai" "jun" "jul" "aug" "sep" "okt" "nov" "des"])

(def ^:private month-names-no-full
  ["Januar" "Februar" "Mars" "April" "Mai" "Juni"
   "Juli" "August" "September" "Oktober" "November" "Desember"])

(defn- month-offset-label
  "Render a schedule-row month (1-indexed) as the full Norwegian month name,
   walking forward from today. Year is shown separately on the year-header row."
  [m]
  (let [today (js/Date.)
        total-m (+ (* (.getFullYear today) 12) (.getMonth today) (dec m))
        m0 (mod total-m 12)]
    (nth month-names-no-full m0)))

(defn- group-schedule-by-year
  "Partition schedule rows by calendar year (walking forward from today).
   Returns a seq of [year rows]."
  [schedule]
  (let [today (js/Date.)
        base-m (+ (* (.getFullYear today) 12) (.getMonth today))
        year-of (fn [row] (quot (+ base-m (dec (:month row))) 12))]
    (->> schedule
         (partition-by year-of)
         (map (fn [grp] [(year-of (first grp)) grp])))))

(defn- loan-overview-tab [loan summary sim-atom initial-sim state-atom]
  (let [sim @sim-atom
        rate-pct (parse-num-or (:rate-pct sim) (* 100 (:nominal-rate loan)))
        payment  (parse-num-or (:monthly-payment sim) (:monthly-payment loan))
        lump     (max 0 (parse-num-or (:lump-sum sim) 0))
        sim-loan (simulated-loan loan sim)
        sim-summary (-> (loan-svc/loan-summary sim-loan)
                        (assoc :paid-schedule (:paid-schedule summary)))
        monthly-fee (or (:monthly-fee loan) 0)
        r-monthly   (loan-svc/monthly-rate (/ rate-pct 100.0))
        min-payment (+ (* (:balance sim-loan) r-monthly) monthly-fee)
        payment-too-low? (<= (- payment monthly-fee) (* (:balance sim-loan) r-monthly))
        show-plan-table? (:show-plan-table? @state-atom)
        ;; Current values
        cur-rate     (:nominal-rate loan)
        cur-payment  (:monthly-payment loan)
        cur-months   (:remaining-months summary)
        cur-total    (loan-total-cost summary)
        cur-interest (+ (or (:paid-interest summary) 0)
                        (or (:remaining-interest summary) 0))
        paid-p (or (:paid-principal summary) 0)
        paid-i (or (:paid-interest summary) 0)
        paid-f (or (:paid-fees summary) 0)
        rem-p  (or (:remaining-principal summary) 0)
        rem-i  (or (:remaining-interest summary) 0)
        rem-f  (or (:remaining-fees summary) 0)
        cur-betalt    (+ paid-p paid-i paid-f)
        cur-gjenstar  (+ rem-p rem-i rem-f)
        cur-original  (or (:original-amount loan) (+ paid-p rem-p))
        ;; Simulated values
        sim-rate     (/ rate-pct 100.0)
        sim-months   (:remaining-months sim-summary)
        sim-total    (loan-total-cost sim-summary)
        sim-interest (+ (or (:paid-interest sim-summary) 0)
                        (or (:remaining-interest sim-summary) 0))
        sim-gjenstar (+ (or (:remaining-principal sim-summary) 0)
                        (or (:remaining-interest sim-summary) 0)
                        (or (:remaining-fees sim-summary) 0))
        ;; Stripe palettes
        c-paid-p (:paid-principal seg-colors)
        c-paid-i (:paid-interest seg-colors)
        c-paid-f (:paid-fees seg-colors)
        c-rem-p  (:rem-principal seg-colors)
        c-rem-i  (:rem-interest seg-colors)
        c-rem-f  (:rem-fees seg-colors)
        total-stripes (cond-> [c-paid-p c-paid-i]
                        (pos? paid-f) (conj c-paid-f)
                        true          (conj c-rem-p c-rem-i)
                        (pos? rem-f)  (conj c-rem-f))
        betalt-stripes   (cond-> [c-paid-p c-paid-i] (pos? paid-f) (conj c-paid-f))
        gjenstar-stripes (cond-> [c-rem-p c-rem-i]   (pos? rem-f)  (conj c-rem-f))
        rente-stripes    [c-paid-i c-rem-i]
        original-stripes [c-paid-p c-rem-p]
        ;; Rate formatter operates on raw decimal (0.0644 → "6.44%")
        rate-fmt (fn [r] (str (gstring/format "%.2f" (* 100 r)) "%"))]
    [:div
     ;; What-if inputs
     [:div.whatif
      [:div.whatif-inputs
       [:label "RENTE (%)"
        [:input.be-input.a-right
         {:type "number" :step "0.01" :value (:rate-pct sim)
          :on-change #(swap! sim-atom assoc :rate-pct (-> % .-target .-value))}]]
       [:label "MÅNEDLIG BETALING"
        [:input.be-input.a-right
         {:type "number" :step "100" :value (:monthly-payment sim)
          :on-change #(swap! sim-atom assoc :monthly-payment (-> % .-target .-value))}]]
       [:label "ENGANGSINNBETALING"
        [:input.be-input.a-right
         {:type "number" :step "1000" :value (:lump-sum sim)
          :on-change #(swap! sim-atom assoc :lump-sum (-> % .-target .-value))}]]
       [pill-button {:variant :ghost-xs
                     :on-click #(reset! sim-atom initial-sim)}
        "Tilbakestill"]]
      (when payment-too-low?
        [:div.whatif-warn
         (str "Betalingen er for lav til å betjene renten. Minimum ca "
              (format-kr min-payment) "/mnd.")])]
     ;; Information islands
     [:div.info-grid
      [info-island
       (merge {:label "Rente" :colors []
               :value (rate-fmt cur-rate)}
              (delta-fields cur-rate sim-rate rate-fmt :colored? false))]
      [info-island
       (merge {:label "Termin" :colors []
               :value (format-kr cur-payment)}
              (delta-fields cur-payment payment format-kr :colored? false))]
      [info-island
       (merge {:label "Lengde" :colors []
               :value (format-duration cur-months)}
              (delta-fields cur-months sim-months format-duration
                            :value-fn format-duration-capped))]
      [info-island
       {:label "Opprinnelig lånebeløp" :colors original-stripes
        :value (format-kr cur-original)}]
      [info-island
       (merge {:label "Total kostnad" :colors total-stripes
               :value (format-kr cur-total)}
              (delta-fields cur-total sim-total format-kr))]
      [info-island
       (merge {:label "Total rente" :colors rente-stripes
               :value (format-kr cur-interest)}
              (delta-fields cur-interest sim-interest format-kr))]
      [info-island
       {:label "Betalt så langt" :colors betalt-stripes
        :value (format-kr cur-betalt)}]
      [info-island
       (merge {:label "Gjenstår" :colors gjenstar-stripes
               :value (format-kr cur-gjenstar)}
              (delta-fields cur-gjenstar sim-gjenstar format-kr))]]
     ;; Chart
     [:div.loan-chart-wrap
      ^{:key (str rate-pct "-" payment "-" lump)}
      [loan-detail-chart sim-summary]]
     [:div.plan-table-toggle
      [pill-button {:variant :ghost-xs
                    :on-click #(swap! state-atom update :show-plan-table? not)}
       (if show-plan-table? "Skjul terminplan" "Vis terminplan")]]
     (when show-plan-table?
       [:div.plan-table-wrap
        [:table.schedule-table
         [:thead
          [:tr
           [:th "Måned"]
           [:th.a-right "Avdrag"]
           [:th.a-right "Rente"]
           [:th.a-right "Termin"]
           [:th.a-right "Rest gjeld"]]]
         [:tbody
          (let [expanded-years (or (:expanded-years @state-atom) #{})
                grouped (group-schedule-by-year (:schedule sim-summary))]
            (doall
             (mapcat
              (fn [[year rows]]
                (let [tot-p   (reduce + 0 (map :principal rows))
                      tot-i   (reduce + 0 (map :interest rows))
                      tot-fee (* monthly-fee (count rows))
                      end-bal (:remaining (last rows))
                      open?   (contains? expanded-years year)
                      toggle  #(swap! state-atom update :expanded-years
                                      (fn [s] (let [s (or s #{})]
                                                (if (contains? s year)
                                                  (disj s year)
                                                  (conj s year)))))]
                  (cons
                   ^{:key (str "y" year)}
                   [:tr.year-header {:on-click toggle}
                    [:td [:span.year-caret (if open? "▾" "▸")] " " year
                     [:span.dim {:style {:margin-left "8px" :font-weight 400
                                         :color "var(--text-dim)"}}
                      (str "(" (count rows) " mnd)")]]
                    [:td.a-right (format-kr tot-p)]
                    [:td.a-right (format-kr tot-i)]
                    [:td.a-right (format-kr (+ tot-p tot-i tot-fee))]
                    [:td.a-right (format-kr end-bal)]]
                   (when open?
                     (for [row rows]
                       ^{:key (str "m" (:month row))}
                       [:tr.month-row
                        [:td.indent (month-offset-label (:month row))]
                        [:td.a-right (format-kr (:principal row))]
                        [:td.a-right (format-kr (:interest row))]
                        [:td.a-right (format-kr (+ (:principal row) (:interest row) monthly-fee))]
                        [:td.a-right (format-kr (:remaining row))]])))))
              grouped)))]]])]))

(defn- loan-historikk-tab [loan matching-txns history loan-params
                           editing-history? history-state update-feedback]
  [:div
   [:div.block-title "Historikk"]
   (if @editing-history?
     [:div.history-edit-wrap
      [loan-history-editor history-state loan-params matching-txns]
      [:div.history-edit-actions
       [pill-button {:variant :primary-xs
                     :on-click (fn []
                                 (let [segs (loan-history-derive-segments
                                             @history-state loan-params matching-txns)]
                                   (dispatch [:store-loan
                                              (cond-> loan
                                                (seq segs) (assoc :payment-history segs))])
                                   (reset! editing-history? false)))}
        "Lagre"]
       [pill-button {:variant :ghost-xs
                     :on-click #(reset! editing-history? false)}
        "Avbryt"]]]
     [:div
      (if (seq history)
        [:div.loan-history-list
         (for [[i seg] (map-indexed vector history)]
           ^{:key i}
           [:div.loan-history-row
            [:span.lh-date (format-month-ms (:date seg))]
            [:span.lh-type (type-label (:type seg))]
            [:span.lh-amount (str (format-kr (:amount seg)) " /mnd")]
            [:span.lh-rate (when (:rate seg) (str "rente " (format-pct (:rate seg))))]])]
        [:div.loan-history-empty "Ingen lagret historikk."])
      (when (seq matching-txns)
        [:div.loan-history-actions
         [pill-button {:variant :ghost-xs
                       :on-click (fn []
                                   (reset! history-state
                                           (loan-history-init-state matching-txns history 0.06))
                                   (reset! editing-history? true))}
          "Endre historikk"]
         [pill-button {:variant :ghost-xs
                       :disabled (= @update-feedback :working)
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
                                      2500)))}
          (case @update-feedback
            :working   "Oppdaterer..."
            :updated   "✓ Oppdatert"
            :unchanged "Ingen endringer"
            "Oppdater historikk")]])])])

(defn- loan-card [_loan _view-state _expanded-id]
  (let [state (r/atom {:tab :oversikt :show-plan-table? false
                       :expanded-years #{(.getFullYear (js/Date.))}})
        sim   (r/atom nil)
        editing-history? (r/atom false)
        history-state    (r/atom nil)
        update-feedback  (r/atom nil)]
    (fn [loan view-state expanded-id]
      (let [summary    (loan-svc/loan-summary loan)
            history    (:payment-history loan)
            all-txns   @(subscribe [:all-transactions])
            filter-texts (or (:filter-texts loan)
                             (when (seq (:filter-text loan))
                               [(:filter-text loan)])
                             [])
            provider   (first filter-texts)
            matching-txns (when (seq filter-texts)
                            (vec (->> all-txns
                                      (filter (fn [tx]
                                                (some #(category/match-fun (:description tx) %)
                                                      filter-texts)))
                                      (sort-by :date))))
            loan-params {:nominal-rate    (:nominal-rate loan)
                         :monthly-payment (:monthly-payment loan)
                         :monthly-fee     (or (:monthly-fee loan) 0)
                         :balance         (:balance loan)
                         :original-amount (:original-amount loan)}
            initial-sim {:rate-pct        (gstring/format "%.3f" (* 100 (:nominal-rate loan)))
                         :monthly-payment (str (:monthly-payment loan))
                         :lump-sum        "0"}
            _ (when (nil? @sim) (reset! sim initial-sim))
            paid-pct (if (and (:original-amount loan) (pos? (:original-amount loan)))
                       (* 100.0 (/ (or (:paid-principal summary) 0) (:original-amount loan)))
                       0)
            expanded? (= @expanded-id (:id loan))
            tab       (:tab @state)
            toggle    #(reset! expanded-id (if expanded? nil (:id loan)))]
        [:div.loan-card {:class (when expanded? "is-open")
                         :id (str "loan-card-" (:id loan))}
         [:div.loan-card-head {:on-click toggle}
          [:div.loan-card-title
           [:div.loan-name (:name loan)]
           [:div.loan-provider (or provider "—")]]
          [:div.loan-stats
           [:div.loan-stat
            [:div.loan-stat-label "REST"]
            [:div.loan-stat-value (format-kr (:remaining-principal summary))]]
           [:div.loan-stat
            [:div.loan-stat-label "RENTE"]
            [:div.loan-stat-value (str (gstring/format "%.2f" (* 100 (:nominal-rate loan))) "%")]]
           [:div.loan-stat
            [:div.loan-stat-label "TERMIN"]
            [:div.loan-stat-value (format-kr (:monthly-payment loan))]]
           [:div.loan-stat
            [:div.loan-stat-label "FERDIG OM"]
            [:div.loan-stat-value (format-duration (:remaining-months summary))]]
           [:div.loan-stat
            [:div.loan-stat-label "NEDBETALT"]
            [:div.loan-stat-value.pos (str (fmt/format-amount paid-pct) "%")]]]
          [:div.loan-card-caret [icon :caret-down]]]
         (when expanded?
           [:div.loan-card-body
            [:div.loan-tabs
             (for [[k label] [[:oversikt "Oversikt"] [:historikk "Historikk"]]]
               ^{:key k}
               [:button.loan-tab {:class (when (= k tab) "is-active")
                                  :on-click #(swap! state assoc :tab k)}
                label])]
            (case tab
              :historikk [loan-historikk-tab loan matching-txns history loan-params
                          editing-history? history-state update-feedback]
              [loan-overview-tab loan summary sim initial-sim state])
            [:div.loan-card-actions
             [pill-button {:variant :ghost
                           :on-click #(reset! view-state {:view :edit :loan loan})}
              "Rediger"]
             [pill-button {:variant :danger
                           :on-click (fn []
                                       (when (js/confirm "Slett dette lånet?")
                                         (dispatch [:delete-loan (:id loan)])))}
              "Slett"]]])]))))

(defn- loan-list-view [_loans _view-state]
  (let [expanded-id (r/atom nil)]
    (fn [loans view-state]
      [:div
       [:div.page-header
        [:h2 "Lån"]
        [:div.right
         [pill-button {:variant :primary
                       :on-click #(reset! view-state {:view :new})}
          "+ Nytt lån"]]]
       (if (empty? loans)
         [:p {:style {:color "var(--text-dim)" :margin-top "24px"}}
          "Ingen lån lagt til ennå."]
         (let [summaries (->> loans
                              (mapv (fn [l] [l (loan-svc/loan-summary l)]))
                              (sort-by (fn [[_ s]] (loan-total-cost s)) >))
               max-total (apply max (map (fn [[_ s]] (loan-total-cost s)) summaries))]
           [:<>
            [loan-summary-tiles summaries]
            [loan-comparison-panel summaries max-total expanded-id]
            [:div.loan-cards
             (for [[loan _summary] summaries]
               ^{:key (:id loan)}
               [loan-card loan view-state expanded-id])]]))])))
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
        [:div
         (case (:view @view-state)
           :list [loan-list-view loans view-state]
           :new  [loan-form view-state]
           :edit [loan-form view-state {:editing-loan (:loan @view-state)}]
           [loan-list-view loans view-state])]))))

;; ---------- User ----------

(defn- user-info-bar []
  [:div {:style {:display "flex" :align-items "center" :justify-content "flex-end"
                 :padding "8px 16px" :border-bottom "1px solid #eee"}}
   [user-menu]])

(defn- fmt-appreciation [a]
  (when (some? a)
    (str (when (>= a 0) "+") (gstring/format "%.1f" (* 100 a)) " %")))

(defn- wealth-chart-inner
  "Form-3 component that (re)draws the D3 wealth chart when its inputs change."
  [_points _buy-bands _sell-bands _trades _leverage]
  (let [draw! (fn [this]
                (let [[_ pts bb sb trs lev] (r/argv this)]
                  (wealth-chart/draw-wealth-chart "#wealth-chart" pts bb sb trs lev)))]
    (r/create-class
     {:display-name "wealth-chart-inner"
      :component-did-mount  draw!
      :component-did-update (fn [this _] (draw! this))
      :reagent-render       (fn [_points _buy-bands _sell-bands _trades _leverage] [:div {:id "wealth-chart"}])})))

(defn- leverage-panel
  "Editable belåningsgrad (collateral ratio) per holding + Nordnet screener link."
  [assets settings]
  [:div {:style {:margin "20px 0"}}
   [:div.status-label {:style {:margin-bottom "6px"}} "BELÅNINGSGRAD PER AKTIVA"]
   [:p.dim.small {:style {:margin "0 0 8px"}}
    "Nordnet har ingen åpen API for belåningsgrad. Slå opp grader i "
    [:a.link-ext {:href "https://www.nordnet.no/market/stocks?sortField=instrument_pawn_percentage&sortOrder=descending&selectedTab=keyFigures&exchangeCountry=US"
                  :target "_blank"}
     "Nordnets markedsoversikt"]
    " (sorter på «Belåningsgrad»; bytt exchangeCountry=NO/US osv.) og legg dem inn her."]
   [:p.dim.small {:style {:margin "0 0 8px"}}
    "Maks belåning justeres dynamisk etter Nordnets tabell: en diversifisert portefølje "
    "(liten største posisjon) gir høyere effektiv belåningsgrad enn den oppgitte, mens en "
    "konsentrert portefølje gir lavere. Effektiv grad slås opp fra oppgitt grad × største "
    "posisjons andel av porteføljen."]
   [:table.txn-table
    [:thead [:tr [:th "Aktiva"] [:th.a-right "Belåningsgrad %"]]]
    [:tbody
     (for [a assets]
       ^{:key (:isin a)}
       [:tr.txn-row
        [:td (:name a)]
        [:td.a-right
         [:input.form-input
          {:type "number" :min 0 :max 100 :step 1
           :style {:width "90px" :text-align "right"}
           :default-value (js/Math.round (* 100 (or (get settings (:isin a)) 0)))
           :on-blur #(let [pct (js/parseFloat (.. % -target -value))
                           frac (/ (if (js/isNaN pct) 0 pct) 100.0)]
                       (dispatch [:save-leverage-setting (:isin a) frac]))}]]])]]])

(defn- formue-content []
  (r/create-class
   {:display-name "formue-content"
    :component-did-mount #(do (dispatch [:request-wealth-data])
                              (dispatch [:request-leverage-settings]))
    :reagent-render
    (fn []
      (let [data     @(subscribe [:wealth-data])
            loading? @(subscribe [:wealth-loading?])
            selected @(subscribe [:wealth-selected-asset])
            lev-settings @(subscribe [:leverage-settings])
            assets   (:assets data)
            ;; both the total and each asset share the same shape:
            ;; {:points :buy-bands :sell-bands :trades}
            scope    (if (= selected :total)
                       (:total data)
                       (first (filter #(= selected (:isin %)) assets)))
            points   (:points scope)
            trades   (:trades scope)
            buy-bands  (:buy-bands scope)
            sell-bands (:sell-bands scope)
            ;; leverage is account-level → only shown in the Total view
            leverage (when (= selected :total) (:leverage (:total data)))
            options  (into [[:total "Total"]]
                           (map (fn [a] [(:isin a) (:name a)]) assets))
            latest   (last points)
            method   @(subscribe [:wealth-method])
            refreshing? @(subscribe [:prices-refreshing?])
            updated-at @(subscribe [:prices-updated-at])]
        [:div
         [:div.page-header
          [:h2 "Formue"]
          [:div.right {:style {:display "flex" :align-items "center" :gap "10px"}}
           (when (and updated-at (not refreshing?))
             [:span.dim.small (str "Priser oppdatert " (format-last-sync updated-at))])
           [pill-button {:variant :ghost
                         :disabled refreshing?
                         :on-click #(dispatch [:refresh-prices])}
            (if refreshing? "Oppdaterer priser…" "Oppdater priser")]]]
         (cond
           loading?
           [:p.dim {:style {:margin "20px 0"}} "Laster…"]

           (empty? (get-in data [:total :points]))
           [:p {:style {:color "var(--text-dim)" :margin "20px 0"}}
            "Ingen investeringer ennå. Legg til en Nordnet-konto under Kontoer og importer en transaksjons-CSV."]

           :else
           [:div
            [:div.account-summary
             [:div.status-label "PORTEFØLJEVERDI"]
             [:div.big-mono (format-kr (js/Math.round (:value latest)))]
             [:div.status-sub
              (if-let [a (fmt-appreciation (:appreciation latest))]
                (str "Verdiendring siden kjøp: " a)
                "—")]]
            [:div {:style {:display "flex" :flex-wrap "wrap" :gap "16px"
                           :align-items "flex-end" :margin "12px 0"}}
             (when (> (count options) 2)
               [:div
                [:div.status-label {:style {:margin-bottom "4px"}} "AKTIVA"]
                [segmented {:options options
                            :selected selected
                            :on-select #(dispatch [:set-wealth-asset %])}]])
             [:div
              [:div.status-label {:style {:margin-bottom "4px"}} "KOSTPRIS-METODE"]
              [segmented {:options [[:fifo "FIFO"] [:lifo "LIFO"]]
                          :selected method
                          :on-select #(dispatch [:set-wealth-method %])}]]
             [:div {:style {:display "flex" :gap "14px" :align-items "center" :flex-wrap "wrap"
                            :margin-left "auto" :font-size "12px" :color "var(--text-dim)"}}
              [:span {:style {:color "#2563eb"}} "▲ Kjøp"]
              [:span {:style {:color "#f59e0b"}} "▼ Salg"]
              (when (= selected :total)
                [:span {:style {:color "#9ca3af"}} "▬ Maks belåning"])
              (when (= selected :total)
                [:span {:style {:color "#b91c1c"}} "▬ Brukt belåning"])]]
            [wealth-chart-inner (vec points) (vec buy-bands) (vec sell-bands) (vec trades) (vec leverage)]
            [leverage-panel assets lev-settings]])]))}))

(defn odin-app []
  (let [auth @(subscribe [:auth])]
    (if (or (nil? (:token auth)) (and (:loading? auth) (nil? (:user auth))))
      (if (:loading? auth)
        [:div {:style {:display "flex" :justify-content "center" :margin-top "200px"}}
         [:p "Laster..."]]
        [auth-view])
      (let [active-menu @(subscribe [:active-menu])]
        [:div.app-shell
         [nav-sidebar]
         [:main.main-area
          [:div.content-scroll
           [reauth-banner]
           (case active-menu
             :transaksjoner [transaksjoner-content]
             :budsjett [budsjett-content]
             :dagligvarer [dagligvarer-content]
             :laan [loans-content]
             :kontoer [kontoer-content]
             :formue [formue-content]
             :rapporter [reports/reports-content]
             [transaksjoner-content])]]]))))
