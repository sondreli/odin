(ns client.components.reports.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [clojure.string :as s]
            [client.services.expression-service :as expr]
            [client.services.report-chart-service :as report-chart]
            [client.services.date-service :as date]
            [client.services.format-service :as fmt]
            [client.components.period-selector-v2.views :as period-sel-v2]
            [client.components.ui.icon :refer [icon]]
            [client.components.ui.button :refer [pill-button]]
            [client.components.ui.segmented :refer [segmented]]))

(defn- period-granularity
  "Returns :day if single month, :month otherwise."
  [period]
  (let [diff-days (/ (- (.getTime (:end period)) (.getTime (:start period)))
                     1000 60 60 24)]
    (if (< diff-days 32) :day :month)))

(defn- group-label-fn [granularity]
  (if (= granularity :day)
    date/get-date-label
    date/get-month-label))

(defn- transaction-group-key [granularity txn]
  (let [ts (:date txn)
        d (js/Date. ts)]
    (if (= granularity :day)
      (.getDate d)
      (+ (* (.getFullYear d) 100) (.getMonth d)))))

(defn- all-period-keys
  "Generate all expected group keys for a period."
  [granularity period]
  (if (= granularity :day)
    (let [start (:start period)
          end (:end period)]
      (loop [d (js/Date. (.getTime start)) ks []]
        (if (>= (.getTime d) (.getTime end))
          ks
          (recur (js/Date. (.getFullYear d) (.getMonth d) (inc (.getDate d)))
                 (conj ks (.getDate d))))))
    (let [start (:start period)
          end (:end period)]
      (loop [d (js/Date. (.getTime start)) ks []]
        (if (>= (.getTime d) (.getTime end))
          ks
          (recur (js/Date. (.getFullYear d) (inc (.getMonth d)) 1)
                 (conj ks (+ (* (.getFullYear d) 100) (.getMonth d)))))))))

(defn- compute-data-points
  "Given transactions, categories, tags, period, and an expression AST,
   computes data points [{:label ... :value ... :transactions ... :var-map ...}]."
  [transactions categories tags period ast]
  (when (and ast (not (:error ast)))
    (let [granularity (period-granularity period)
          label-fn (group-label-fn granularity)
          grouped (group-by #(transaction-group-key granularity %) transactions)
          all-keys (all-period-keys granularity period)
          sorted-keys (sort all-keys)]
      (mapv (fn [k]
              (let [txns (get grouped k [])
                    label (if (seq txns)
                            (label-fn (:date (first txns)))
                            (if (= granularity :day)
                              (if (< k 10) (str "0" k) (str k))
                              (let [year (quot k 100)
                                    month (rem k 100)]
                                (date/get-month-label (.getTime (js/Date. year month 1))))))
                    var-map (expr/build-variable-map txns categories tags)
                    value (expr/evaluate ast var-map)]
                {:label label :value value :transactions txns :var-map var-map}))
            sorted-keys))))

(defn- report-chart-preview
  "Reagent component that renders a D3 chart. Uses r/create-class for lifecycle."
  [chart-id data-points chart-type & {:keys [on-bar-click]}]
  (let [draw! (fn [cid dps ct cb]
                (case ct
                  "waterfall" (report-chart/draw-waterfall-chart cid dps :on-bar-click cb)
                  (report-chart/draw-bar-chart cid dps :on-bar-click cb)))]
    (r/create-class
     {:display-name "report-chart-preview"
      :reagent-render
      (fn [chart-id _data-points _chart-type & _]
        [:div {:id chart-id :style {:width "100%" :min-height "120px"}}])
      :component-did-mount
      (fn [_this]
        (draw! chart-id data-points chart-type on-bar-click))
      :component-did-update
      (fn [this]
        (let [argv (r/argv this)
              cid (nth argv 1)
              dps (nth argv 2)
              ct (nth argv 3)
              opts (apply hash-map (drop 4 argv))
              cb (:on-bar-click opts)]
          (draw! cid dps ct cb)))})))

(defn- filter-transactions-by-period [all-transactions period]
  (when (and all-transactions period)
    (filterv #(date/transaction-in-period? % (:start period) (:end period))
             all-transactions)))

;; ---- Column Drilldown ----

(defn- format-txn-date [ts]
  (let [d (js/Date. ts)]
    (str (.getFullYear d) "-"
         (let [m (inc (.getMonth d))] (if (< m 10) (str "0" m) (str m))) "-"
         (let [day (.getDate d)] (if (< day 10) (str "0" day) (str day))))))

(defn- transactions-for-variable
  "Returns the transactions that contribute to a given variable name."
  [var-name txns categories tags]
  (let [cat-by-name (into {} (map (juxt :name :id) categories))
        tag-by-name (into {} (map (juxt #(str "tag:" (:name %)) :id) tags))]
    (cond
      (= var-name "alle-inntekter")
      (filterv #(pos? (:amount %)) txns)

      (= var-name "alle-utgifter")
      (filterv #(neg? (:amount %)) txns)

      (contains? tag-by-name var-name)
      (let [tid (get tag-by-name var-name)]
        (filterv #(some #{tid} (:tag-ids %)) txns))

      (contains? cat-by-name var-name)
      (let [cid (get cat-by-name var-name)]
        (filterv #(= (:category-id %) cid) txns))

      :else [])))

(defn- variable-source-label
  "Returns a human-readable source label for a transaction relative to a variable."
  [var-name txn categories _tags]
  (cond
    (#{"alle-inntekter" "alle-utgifter"} var-name)
    (let [cat-id (:category-id txn)
          cat-name (some #(when (= (:id %) cat-id) (:name %)) categories)]
      (or cat-name ""))

    (s/starts-with? var-name "tag:")
    (let [tag-name (subs var-name 4)]
      tag-name)

    :else var-name))

(defn- drilldown-variable-section [var-name var-value txns categories tags]
  (let [sorted-txns (sort-by :date txns)]
    [:div.rep-drill-section
     [:table.rep-drill-table
      [:thead
       [:tr.rep-drill-head
        [:th {:col-span 4}
         [:span.rep-drill-var var-name]
         [:span.rep-drill-var-val (str "= " (fmt/format-amount var-value))]]]]
      [:tbody
       (for [[idx txn] (map-indexed vector sorted-txns)]
         ^{:key idx}
         [:tr.rep-drill-row
          [:td.rep-drill-date (format-txn-date (:date txn))]
          [:td.rep-drill-desc (:description txn)]
          [:td.rep-drill-amt.mono (fmt/format-amount (:amount txn))]
          [:td.rep-drill-src.dim
           (variable-source-label var-name txn categories tags)]])
       [:tr.rep-drill-total
        [:td {:col-span 2} "Sum"]
        [:td.mono (fmt/format-amount (reduce + 0 (map :amount sorted-txns)))]
        [:td.dim (str "abs: " (fmt/format-amount var-value))]]]]]))

(defn- column-drilldown [data-point categories tags ast _expression-str]
  (let [var-map (:var-map data-point)
        txns (:transactions data-point)
        var-names (distinct (expr/extract-variables ast))
        result (:value data-point)]
    [:div.rep-drill
     [:div.rep-drill-head-label (str "Detaljer: " (:label data-point))]
     (for [vname var-names]
       (let [var-value (get var-map vname 0)
             var-txns (transactions-for-variable vname txns categories tags)]
         ^{:key vname}
         [drilldown-variable-section vname var-value var-txns categories tags]))
     [:div.rep-drill-result
      [:span.rep-drill-label "Resultat: "]
      [:span.mono (expr/expression-to-string ast var-map)]
      [:span.rep-drill-equals (str "= " (fmt/format-amount result))]]]))

;; ---- Gallery card ----

(defn- expression-chip [label on-click]
  [:button.chip.mono {:on-click on-click} label])

(defn- format-signed-kr [n]
  (str (when (pos? n) "+") (fmt/format-kr n)))

(defn- report-card [report on-click]
  (let [categories @(subscribe [:categories])
        tags @(subscribe [:tags])
        period @(subscribe [:reports-period])
        transactions (filter-transactions-by-period
                      @(subscribe [:all-transactions]) period)
        parsed (expr/parse-expression (:expression report))
        ast (:ast parsed)
        data-points (when ast
                      (compute-data-points transactions categories tags period ast))
        values (map :value data-points)
        sum (reduce + 0 values)
        n (count values)
        avg (if (pos? n) (/ sum n) 0)
        chart-id (str "report-card-chart-" (:id report))]
    [:div.report-card {:on-click on-click}
     [:div.report-card-head
      [:div.report-name (:name report)]
      [:div.report-expr.mono (:expression report)]]
     (if (seq data-points)
       [:div.report-mini
        [report-chart-preview chart-id data-points (or (:chart-type report) "bar")]]
       [:div.report-mini-empty "—"])
     [:div.report-card-stats
      [:div
       [:div.status-label "SNITT/MND"]
       [:div.mono {:class (if (>= avg 0) "pos" "neg")} (format-signed-kr avg)]]
      [:div
       [:div.status-label "SUM"]
       [:div.mono (format-signed-kr sum)]]]]))

(defn- report-card-new [on-click]
  [:button.report-card.report-new {:on-click on-click}
   [icon :plus {:size 24}]
   [:span "Ny rapport"]])

(defn- open-report [view-state report]
  (reset! view-state {:view :detail :report report})
  (when-let [ps (:period-start report)]
    (let [pe (:period-end report)
          pt (keyword (or (:period-type report) "month"))]
      (dispatch [:set-reports-period {:start (js/Date. (* ps 1000))
                                      :end   (js/Date. (* pe 1000))
                                      :period-type pt}]))))

(defn- reports-gallery [view-state]
  (let [reports @(subscribe [:reports])]
    [:div
     [:div.page-header
      [:h2 "Rapporter"]
      [:div.right
       [pill-button {:variant :primary
                     :on-click #(reset! view-state {:view :detail :report nil})}
        "+ Ny rapport"]]]
     [:div.rep-period-row
      [period-sel-v2/period-selector {:period-sub :reports-period
                                      :on-change  #(dispatch [:set-reports-period %])}]]
     [:div.report-gallery
      (for [r reports]
        ^{:key (:id r)}
        [report-card r #(open-report view-state r)])
      [report-card-new #(reset! view-state {:view :detail :report nil})]]]))

;; ---- Detail view ----

(defn- report-detail [_view-state existing-report]
  (let [report-name    (r/atom (or (:name existing-report) "Ny rapport"))
        expression     (r/atom (or (:expression existing-report) ""))
        chart-type     (r/atom (or (:chart-type existing-report) "bar"))
        selected-label (r/atom nil)]
    (fn [view-state existing-report]
      (let [editing?     (some? existing-report)
            categories   @(subscribe [:categories])
            tags         @(subscribe [:tags])
            period       @(subscribe [:reports-period])
            transactions (filter-transactions-by-period
                           @(subscribe [:all-transactions]) period)
            variables    (expr/available-variables categories tags)
            parsed       (expr/parse-expression @expression)
            ast          (:ast parsed)
            parse-error  (:error parsed)
            data-points  (when ast
                           (compute-data-points transactions categories tags period ast))
            values   (map :value data-points)
            sum      (reduce + 0 values)
            nv       (count values)
            avg      (if (pos? nv) (/ sum nv) 0)
            min-v    (if (seq values) (apply min values) 0)
            max-v    (if (seq values) (apply max values) 0)
            chart-id (str "report-detail-chart-" (or (:id existing-report) "new"))
            selected-dp (when @selected-label
                          (some #(when (= (:label %) @selected-label) %) data-points))
            can-save?   (and (seq @report-name) ast)]
        [:div
         [:div.page-header
          [:button.link.rep-back
           {:on-click #(reset! view-state {:view :list :report nil})}
           "← Tilbake til rapporter"]]

         [:div.rep-detail
          [:div.rep-detail-head
           [:div.rep-title-block
            [:input.rep-name-input
             {:type "text" :value @report-name
              :on-change #(reset! report-name (.. % -target -value))
              :placeholder "Rapportnavn"}]]
           [:div.rep-actions
            [segmented {:options  [["bar" "Stolpe"] ["waterfall" "Fossefall"]]
                        :selected @chart-type
                        :on-select #(reset! chart-type %)}]
            [period-sel-v2/period-selector {:period-sub :reports-period
                                            :on-change  #(dispatch [:set-reports-period %])}]]]

          [:div.rep-expr-builder
           [:label.rep-expr-label "Uttrykk"]
           [:input.rep-expr-input.mono
            {:value @expression
             :placeholder "f.eks. alle-inntekter - alle-utgifter"
             :on-change #(reset! expression (.. % -target -value))}]
           (when parse-error
             [:div.rep-expr-error parse-error])
           [:div.rep-chips
            [:span.rep-chips-label "Variabler:"]
            (for [v variables]
              ^{:key v}
              [expression-chip v #(swap! expression str (if (seq @expression) " " "") v)])
            [:span.rep-chips-label "Operatorer:"]
            (for [op ["+" "-" "*" "/" "(" ")"]]
              ^{:key op}
              [expression-chip op #(swap! expression str " " op " ")])]]

          [:div.rep-result
           (if (seq data-points)
             [:<>
              [report-chart-preview chart-id data-points @chart-type
               :on-bar-click (fn [label]
                               (swap! selected-label
                                      #(if (= % label) nil label)))]
              (when selected-dp
                [column-drilldown selected-dp categories tags ast @expression])]
             [:div.rep-empty "Skriv inn et uttrykk for å se data."])]

          [:div.rep-stats
           [:div.rep-stat
            [:div.status-label "SUM"]
            [:div.mono (format-signed-kr sum)]]
           [:div.rep-stat
            [:div.status-label "SNITT"]
            [:div.mono (fmt/format-kr avg)]]
           [:div.rep-stat
            [:div.status-label "MIN"]
            [:div.mono (fmt/format-kr min-v)]]
           [:div.rep-stat
            [:div.status-label "MAX"]
            [:div.mono (fmt/format-kr max-v)]]]

          [:div.rep-detail-actions
           [pill-button {:variant :primary
                         :disabled (not can-save?)
                         :on-click (fn []
                                     (when can-save?
                                       (dispatch [:store-report
                                                  (cond-> {:name @report-name
                                                           :expression @expression
                                                           :chart-type @chart-type}
                                                    editing? (assoc :id (:id existing-report)))])
                                       (reset! view-state {:view :list :report nil})))}
            (if editing? "Lagre endringer" "Opprett")]
           (when editing?
             [pill-button {:variant :ghost
                           :on-click (fn []
                                       (when can-save?
                                         (dispatch [:store-report
                                                    {:name (str @report-name " (kopi)")
                                                     :expression @expression
                                                     :chart-type @chart-type}])
                                         (reset! view-state {:view :list :report nil})))}
              "Dupliser"])
           (when editing?
             [:button.link.neg-link.rep-delete
              {:on-click (fn []
                           (when (js/confirm "Slett denne rapporten?")
                             (dispatch [:delete-report (:id existing-report)])
                             (reset! view-state {:view :list :report nil})))}
              "Slett rapport"])]]]))))

;; ---- Top-level ----

(defn reports-content []
  (let [view-state (r/atom {:view :list :report nil})]
    (fn []
      (let [{:keys [view report]} @view-state]
        (case view
          :list   [reports-gallery view-state]
          :detail [report-detail view-state report]
          [reports-gallery view-state])))))
