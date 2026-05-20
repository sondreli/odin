(ns client.components.reports.views
  (:require [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as r]
            [clojure.string :as s]
            [client.services.expression-service :as expr]
            [client.services.report-chart-service :as report-chart]
            [client.services.date-service :as date]
            [client.components.period-selector-v2.views :as period-sel-v2]))

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
                            ;; Generate label from key for empty periods
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
        [:div {:id chart-id :style {:width "100%" :min-height "200px"}}])
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

;; ---- Create Report View ----

(defn- expression-chip [label on-click]
  [:button {:on-click on-click
            :style {:padding "3px 10px" :margin "2px"
                    :font-size "12px" :cursor "pointer"
                    :border "1px solid #ccc" :border-radius "12px"
                    :background-color "#f5f5f5"}}
   label])

(defn- filter-transactions-by-period [all-transactions period]
  (when (and all-transactions period)
    (filterv #(date/transaction-in-period? % (:start period) (:end period))
             all-transactions)))

(defn report-create [{:keys [_on-back existing-report]}]
  (let [report-name (r/atom (or (:name existing-report) ""))
        expression (r/atom (or (:expression existing-report) ""))
        chart-type (r/atom (or (:chart-type existing-report) "bar"))
        editing? (some? existing-report)]
    (fn [{:keys [on-back _existing-report]}]
      (let [categories @(subscribe [:categories])
            tags @(subscribe [:tags])
            period @(subscribe [:reports-period])
            transactions (filter-transactions-by-period
                          @(subscribe [:all-transactions]) period)
            variables (expr/available-variables categories tags)
            parsed (expr/parse-expression @expression)
            ast (:ast parsed)
            parse-error (:error parsed)
            data-points (when ast
                          (compute-data-points transactions categories tags period ast))
            radio-name (if editing? "chart-type-edit" "chart-type")]
        [:div {:style {:max-width "900px"}}
         [:h3 (if editing? "Rediger rapport" "Ny rapport")]

         [:div {:style {:margin-bottom "12px"}}
          [:label {:style {:font-weight "600" :display "block" :margin-bottom "4px"}} "Navn"]
          [:input {:type "text" :value @report-name
                   :on-change #(reset! report-name (-> % .-target .-value))
                   :placeholder "Rapportnavn"
                   :style {:width "300px" :padding "6px" :border "1px solid #ccc"
                           :border-radius "4px"}}]]

         [:div {:style {:margin-bottom "12px"}}
          [:label {:style {:font-weight "600" :display "block" :margin-bottom "4px"}} "Uttrykk"]
          [:input {:type "text" :value @expression
                   :on-change #(reset! expression (-> % .-target .-value))
                   :placeholder "f.eks. alle-inntekter - alle-utgifter"
                   :style {:width "100%" :padding "6px" :border "1px solid #ccc"
                           :border-radius "4px" :font-family "monospace"
                           :box-sizing "border-box"}}]
          (when parse-error
            [:div {:style {:color "#ef4444" :font-size "12px" :margin-top "4px"}}
             parse-error])

          [:div {:style {:margin-top "8px"}}
           [:span {:style {:font-size "12px" :color "#888" :margin-right "6px"}} "Variabler:"]
           (doall
            (for [v variables]
              ^{:key v}
              [expression-chip v #(swap! expression str (if (seq @expression) " " "") v)]))]

          [:div {:style {:margin-top "4px"}}
           [:span {:style {:font-size "12px" :color "#888" :margin-right "6px"}} "Operatorer:"]
           (doall
            (for [op ["+" "-" "*" "/" "(" ")"]]
              ^{:key op}
              [expression-chip op #(swap! expression str " " op " ")]))]]

         [:div {:style {:margin-bottom "16px"}}
          [:label {:style {:font-weight "600" :display "block" :margin-bottom "4px"}} "Diagramtype"]
          [:label {:style {:margin-right "16px" :cursor "pointer"}}
           [:input {:type "radio" :name radio-name :value "bar"
                    :checked (= @chart-type "bar")
                    :on-change #(reset! chart-type "bar")
                    :style {:margin-right "4px"}}]
           "Stolpediagram"]
          [:label {:style {:cursor "pointer"}}
           [:input {:type "radio" :name radio-name :value "waterfall"
                    :checked (= @chart-type "waterfall")
                    :on-change #(reset! chart-type "waterfall")
                    :style {:margin-right "4px"}}]
           "Fossefallsdiagram"]]

         (when (seq data-points)
           [:div {:style {:margin-bottom "16px"
                          :border "1px solid #eee" :border-radius "6px"
                          :padding "12px" :background-color "#fafafa"}}
            [:div {:style {:font-size "13px" :color "#888" :margin-bottom "6px"}} "Forhåndsvisning"]
            [report-chart-preview "report-preview-chart" data-points @chart-type]])

         [:div {:style {:display "flex" :gap "8px"}}
          [:button {:on-click (fn []
                                (when (and (seq @report-name) ast)
                                  (dispatch [:store-report
                                             (cond-> {:name @report-name
                                                      :expression @expression
                                                      :chart-type @chart-type}
                                               editing? (assoc :id (:id existing-report)))])
                                  (on-back)))
                    :disabled (or (empty? @report-name) (nil? ast))
                    :style {:padding "8px 20px" :cursor "pointer"
                            :opacity (if (and (seq @report-name) ast) "1" "0.5")}}
           (if editing? "Lagre" "Opprett")]
          [:button {:on-click on-back
                    :style {:padding "8px 20px" :cursor "pointer"}}
           "Avbryt"]]]))))

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
    [:div {:style {:margin-bottom "16px"}}
     [:table {:style {:width "100%" :border-collapse "collapse" :font-size "12px"}}
      [:thead
       [:tr {:style {:background-color "#f0f4f8"}}
        [:th {:col-span 4
              :style {:text-align "left" :padding "6px 8px" :font-weight "600"}}
         [:span var-name]
         [:span {:style {:font-weight "normal" :color "#666" :margin-left "12px"}}
          (str "= " (.toFixed (Math/abs var-value) 2))]]]]
      [:tbody
       (doall
        (for [[idx txn] (map-indexed vector sorted-txns)]
          ^{:key idx}
          [:tr {:style {:border-bottom "1px solid #eee"}}
           [:td {:style {:padding "3px 8px" :color "#888" :white-space "nowrap"}}
            (format-txn-date (:date txn))]
           [:td {:style {:padding "3px 8px"}}
            (:description txn)]
           [:td {:style {:padding "3px 8px" :text-align "right" :font-family "monospace"
                         :white-space "nowrap"}}
            (.toFixed (:amount txn) 2)]
           [:td {:style {:padding "3px 8px" :color "#888"}}
            (variable-source-label var-name txn categories tags)]]))
       [:tr {:style {:border-top "2px solid #ccc" :font-weight "600"}}
        [:td {:col-span 2 :style {:padding "4px 8px"}} "Sum"]
        [:td {:style {:padding "4px 8px" :text-align "right" :font-family "monospace"}}
         (.toFixed (reduce + 0 (map :amount sorted-txns)) 2)]
        [:td {:style {:padding "4px 8px" :text-align "right" :font-family "monospace"
                       :color "#666"}}
         (str "abs: " (.toFixed var-value 2))]]]]]))

(defn- column-drilldown [data-point categories tags ast _expression-str]
  (let [var-map (:var-map data-point)
        txns (:transactions data-point)
        var-names (distinct (expr/extract-variables ast))
        result (:value data-point)]
    [:div {:style {:margin-top "16px" :border "1px solid #ddd" :border-radius "6px"
                   :padding "16px" :background-color "#fafafa"}}
     [:div {:style {:display "flex" :align-items "center" :gap "8px" :margin-bottom "12px"}}
      [:h4 {:style {:margin 0}} (str "Detaljer: " (:label data-point))]]
     (doall
      (for [vname var-names]
        (let [var-value (get var-map vname 0)
              var-txns (transactions-for-variable vname txns categories tags)]
          ^{:key vname}
          [drilldown-variable-section vname var-value var-txns categories tags])))
     [:div {:style {:margin-top "8px" :padding "10px 12px"
                    :background-color "#e8ecf1" :border-radius "4px"
                    :font-family "monospace" :font-size "13px"}}
      [:span {:style {:font-weight "600"}} "Resultat: "]
      [:span (expr/expression-to-string ast var-map)]
      [:span {:style {:font-weight "700" :margin-left "8px"}}
       (str "= " (.toFixed result 2))]]]))

;; ---- View Single Report ----

(defn- report-viewer [_report _on-back _on-edit]
  (let [selected-label (r/atom nil)]
    (fn [report on-back on-edit]
      (let [categories @(subscribe [:categories])
            tags @(subscribe [:tags])
            period @(subscribe [:reports-period])
            transactions (filter-transactions-by-period
                          @(subscribe [:all-transactions]) period)
            parsed (expr/parse-expression (:expression report))
            ast (:ast parsed)
            data-points (when ast
                          (compute-data-points transactions categories tags period ast))
            chart-id (str "report-view-" (:id report))
            selected-dp (when @selected-label
                          (some #(when (= (:label %) @selected-label) %) data-points))]
        [:div
         [:div {:style {:display "flex" :align-items "center" :gap "12px" :margin-bottom "12px"}}
          [:a {:class "cursor-pointer" :on-click on-back
               :style {:font-size "13px"}} "← Tilbake"]
          [:h3 {:style {:margin 0}} (:name report)]
          [:span {:style {:font-size "12px" :color "#888"}}
           (if (= (:chart-type report) "waterfall") "Fossefallsdiagram" "Stolpediagram")]
          [:a {:class "cursor-pointer" :on-click #(on-edit report)
               :style {:font-size "13px" :color "#4f86c6"}} "Rediger"]]
         [:div {:style {:font-size "12px" :color "#666" :margin-bottom "12px"
                        :font-family "monospace" :background-color "#f5f5f5"
                        :padding "6px 10px" :border-radius "4px"}}
          (:expression report)]
         (if (seq data-points)
           [:div
            [report-chart-preview chart-id data-points (or (:chart-type report) "bar")
             :on-bar-click (fn [label]
                             (swap! selected-label
                                    #(if (= % label) nil label)))]
            (when selected-dp
              [column-drilldown selected-dp categories tags ast (:expression report)])]
           [:div {:style {:color "#888" :padding "20px"}} "Ingen data for valgt periode"])]))))

;; ---- Reports List ----

(defn- reports-list [on-create on-view]
  (let [reports @(subscribe [:reports])]
    [:div
     [:div {:style {:display "flex" :align-items "center" :justify-content "space-between"
                    :margin-bottom "16px"}}
      [:h3 {:style {:margin 0}} "Rapporter"]
      [:button {:on-click on-create
                :style {:padding "6px 16px" :cursor "pointer"}}
       "Ny rapport"]]
     (if (seq reports)
       [:table {:style {:width "100%"}}
        [:thead
         [:tr
          [:th {:style {:text-align "left"}} "Navn"]
          [:th {:style {:text-align "left"}} "Type"]
          [:th {:style {:text-align "left"}} "Uttrykk"]
          [:th]]]
        [:tbody
         (doall
          (for [report reports]
            ^{:key (:id report)}
            [:tr
             [:td [:a {:class "cursor-pointer"
                       :style {:color "#4f86c6"}
                       :on-click #(on-view report)}
                   (:name report)]]
             [:td {:style {:font-size "13px" :color "#666"}}
              (if (= (:chart-type report) "waterfall") "Fossefallsdiagram" "Stolpediagram")]
             [:td {:style {:font-size "12px" :color "#888" :font-family "monospace"}}
              (:expression report)]
             [:td [:a {:class "cursor-pointer"
                       :style {:color "#c00" :font-size "13px"}
                       :on-click #(dispatch [:delete-report (:id report)])}
                   "Slett"]]]))]]
       [:div {:style {:color "#888" :padding "20px"}}
        "Ingen rapporter opprettet ennå."])]))

;; ---- Top-level Reports Content ----

(defn- open-report [view-state report]
  (reset! view-state {:view :detail :report report})
  (when-let [ps (:period-start report)]
    (let [pe (:period-end report)
          pt (keyword (or (:period-type report) "month"))]
      (dispatch [:set-reports-period {:start (js/Date. (* ps 1000))
                                      :end   (js/Date. (* pe 1000))
                                      :period-type pt}]))))

(defn reports-content []
  (let [view-state (r/atom {:view :list :report nil})]
    (fn []
      (let [{:keys [view report]} @view-state
            go-list #(reset! view-state {:view :list :report nil})
            go-edit (fn [r] (reset! view-state {:view :edit :report r}))]
        [:div
         [period-sel-v2/period-selector {:period-sub :reports-period
                                        :on-change #(dispatch [:set-reports-period %])}]
         (case view
           :list [reports-list
                  #(reset! view-state {:view :create :report nil})
                  #(open-report view-state %)]
           :create [report-create {:on-back go-list}]
           :detail [report-viewer report go-list go-edit]
           :edit [report-create {:on-back go-list
                                 :existing-report report}]
           [reports-list
            #(reset! view-state {:view :create :report nil})
            #(open-report view-state %)])]))))
