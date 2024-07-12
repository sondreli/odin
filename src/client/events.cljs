(ns client.events
  (:require [ajax.core :as ajax]
            [day8.re-frame.http-fx]
            [client.views]
            [client.db :refer [default-db]]
            [client.events.period-selector :as period-selector]
            [client.events.displayed-transactions-viewer]
            [client.events.utils :as utils]
            [client.services.date-service :as date]
            [client.services.chart-service :as chart]
            [client.services.color-service :as color]
            [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]
            [clojure.string :as s]
            [goog.object :as g]
            [common.category-service :as category]
            ["d3" :as d3]
            
            [goog.string :as gstring]
            [client.routes :as routes]))


(reg-event-db
 :initialise-db
 ;;[check-spec-interceptor]
 (fn [_ _]
   (let [db default-db
         display-option (-> db :displayed-transactions-data :display-option)]
     (routes/start! (:period db) display-option)
     db)))

(reg-event-db
 :get-categories-response
 (fn
   [db [_ response]]
   (let [categories (js->clj response)
         ]
     (-> db
       (assoc :loading "done")
       (assoc :categories categories)))))

(reg-event-db
 :category-stored-in-db-failure
 (fn
   [db [_ response]]
   (println "store-categories-failure")
   db))

(reg-event-db
 :process-response
 (fn
   [db [_ response]]           ;; destructure the response from the event vector
   (let [transactions (js->clj response)
         _ (println "process-response transactions: " (count transactions))
         _ (println "process-response top 10 transactions: " (take 10 transactions))
         transaction-years (date/transaction-years transactions)
         period (:period db)
         updated-period-selector (-> db
                                     :period-selector
                                     (assoc :transaction-years transaction-years))]
     (println "before db assoc")
     (-> db
       (assoc :loading "done") ;; take away that "Loading ..." UI
       (assoc :all-transactions transactions)
       (utils/apply-period updated-period-selector period)))))

(reg-event-db
 :filter-transactions
 (fn
   [db [_ text]]           ;; destructure the response from the event vector
   (let [transactions (:period-transactions db)
         filtered-transactions (->> transactions
                                    (filter #(and (contains? % :description)
                                             (s/includes? (:description %) text)))
                                    (into []))
         ]
     (-> db
       (assoc :displayed-transactions filtered-transactions)))
   ))

(reg-event-fx
 :filter-path
 (fn
   [{db :db
     [_ path-index] :event} _]
   (let [_ (println path-index)
         filter-path (case path-index
                       0 []
                       1 [(-> db :filter-path first)]
                       2 (:filter-path db))]
     (dispatch [:navigate [nil nil filter-path]]))))

(reg-event-db
 :update-builder-category-name
 (fn
   [db [_ text]]
   (-> db
       (assoc-in [:builder-category :name] text))))

(defn is-valid-color? [text]
  (and
   (or (-> text count (= 4))
       (-> text count (= 7)))
   (nil? (re-find #"[^0-9a-fA-F#]" text))))

(reg-event-db
 :update-builder-category-color
 (fn
   [db [_ text]]
   (if (is-valid-color? text)
     (let [period-transactions (:period-transactions db)
           builder-category (-> (:builder-category db)
                                (assoc :color text)
                                (assoc :color-value text))
           marked-transactions (category/mark-transactions builder-category period-transactions)]
       (-> db
           (assoc :builder-category builder-category)
           (assoc :displayed-transactions marked-transactions)))
     (-> db
         (assoc-in [:builder-category :color-value] text)))))

(reg-event-db
 :mark-transactions
 (fn
   [db [_ text]]
   (let [transactions (:period-transactions db)
         builder-category (-> (:builder-category db)
                              (assoc :marker (category/update-marker text)))
         marked-transactions (category/mark-transactions builder-category transactions)
         ]
     (-> db
         (assoc :builder-category builder-category)
         (assoc :displayed-transactions marked-transactions)))
   ))

(defn update-categories [categories builder-category]
  (let [match-index (some (fn [[index category]] (when (= (:id category) (:id builder-category)) index))
                          (map-indexed vector categories))]
    (if (some? match-index)
      (assoc categories match-index builder-category)
      (conj categories builder-category))))

(reg-event-fx
 :store-category3
 (fn
   [{db :db} _]
   (let [builder-category (if (-> db :builder-category :id (= "new-id"))
                            (-> db :builder-category (dissoc :id))
                            (:builder-category db))]
     {:http-xhrio {:method          :post
                   :uri             "http://localhost/category"
                   :params          (clj->js builder-category)
                   :format          (ajax/json-request-format)
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:stored-category-response]
                   :on-failure      [:category-stored-in-db-failure]}
      :db db})))

(reg-event-fx
 :stored-category-response
 (fn
   [{db :db
     [_ response] :event} _]
   (println "stored-category-response: " response)
   (let [stored-category (js->clj response)
         all-transactions (:all-transactions db)
         period-transactions (:period-transactions db)
         _ (println "stored-category-response stored-category: " stored-category)
         accumulator (category/add-category2 all-transactions stored-category)
         updated-all-transactions (:updated-seq accumulator)
         updated-period-transactions (category/add-category period-transactions stored-category)
         updated-categories (update-categories (:categories db) stored-category)
         summed-categories (utils/sum-categoires updated-categories updated-period-transactions)
         updated-db (-> db
                        (assoc :categories updated-categories)
                        (assoc :summed-categories summed-categories)
                        (assoc :builder-category nil)
                        (assoc :all-transactions updated-all-transactions)
                        (assoc :period-transactions updated-period-transactions)
                        (assoc-in [:displayed-transactions-data :displayed-transactions] updated-period-transactions)
                        )]
     (println "store-categories-success")
     {:http-xhrio {:method          :post
                   :uri             "http://localhost/transactions/update"
                   :params          (clj->js (:only-updates accumulator))
                   :format          (ajax/json-request-format)
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:view-category-period [nil nil nil]]
                   :on-failure      [:category-stored-in-db-failure]}
      :db updated-db})))


(reg-event-db
 :delete-category
 (fn
   [db [_ category]]
   db))

(reg-event-fx
 :view-category
 (fn
   [{db :db
     [_ category-name] :event} _]
   (println "requesting categories")
   (let [filter-path (if (and (-> db :filter-path count (= 1))
                              (= category-name (-> db :filter-path first)))
                       []
                       [category-name])]
     (dispatch [:navigate [nil nil filter-path]]))))

(reg-event-db
 :edit-category
 (fn
   [db [_ category-name]]
   (let [edit-category (some #(when (= category-name (:name %)) %) (:categories db))
         updated-categories (filter #(not= (:name %) category-name) (:categories db))]
     (-> db
         (assoc :builder-category edit-category)
         (assoc :categories updated-categories)))))

;; (defn add-textarea [tbody index builder-category]
;;   (let [row (. tbody insertRow (+ index 1))
;;         new-cell (. row insertCell 0)
;;         ;; _ (. new-cell setAttribute "colspan" "3")
;;         ;; _ (. row addEventListener "click" #(dispatch [:toggle-transaction-row index]))
;;         textarea (. js/document createElement "textarea")
;;         _ (g/set textarea "type" "text")
;;         _ (g/set textarea "rows" 5)
;;         _ (g/set textarea "style" "width: 100%")
;;         _ (g/set textarea "value" (-> builder-category :marker :value))
;;         ]
;;     (. new-cell appendChild textarea)))

;; (defn create-option [text]
;;   (let [option (.createElement js/document "option")]
;;     (g/set option "value" text)
;;     (g/set option "text" text)
;;     (g/set option "style" (str "background-color: " text))
;;     option))

;; (defn create-color-select []
;;   (let [color-selector (.createElement js/document "select")
;;         colors (map #(-> [% 0.6 0.9]
;;                          color/hsv2rgb
;;                          color/color-base10->base16
;;                          color/color-str) (color/generate-hues 10))
;;         options (->> colors
;;                      (map create-option)
;;                      (map #(.appendChild color-selector %))
;;                      doall)]
;;     color-selector))

;; (defn enable-editor [index table-index-offset text builder-category]
;;   (let [tbody (. js/document getElementById "categories-tbody")
;;         name-input (. js/document createElement "input")
;;         _ (g/set name-input "type" "text")
;;         _ (g/set name-input "value" text)
;;         this-first-td (-> tbody .-rows (.item index) .-cells (.item 0))
;;         _ (g/set this-first-td "innerHTML" "")
;;         _ (.appendChild this-first-td name-input)
;;         _ (.appendChild this-first-td (create-color-select))

;;         _ (.log js/console tbody)
;;         _ (.log js/console this-first-td)]
;;     (add-textarea tbody index builder-category)))

;; (defn disable-editor [index]
;;   (let [tbody (. js/document getElementById "categories-tbody")
;;         textarea-row (. tbody deleteRow (+ index 1))
;;         _ (.log js/console (-> tbody .-rows (.item index)))
;;         category-name (-> tbody .-rows (.item index) (.getAttribute "value"))
;;         first-td (-> tbody .-rows (.item index) .-cells (.item 0))
;;         _ (.log js/console first-td)
;;         _ (.removeChild first-td (.-lastChild first-td))
;;         _ (.removeChild first-td (.-lastChild first-td))
;;         _ (g/set first-td "innerHTML" category-name)
;;         ]))


;; (reg-event-db
;;  :edit-category2
;;  (fn
;;    [db [_ category-name index]]
;;    (println "edit-category2 " category-name index)
;;    (let [row-index-currently-open (:open-category-row db)
;;          builder-category (some #(when (= category-name (:name %)) %) (:categories db))
;;          updated-categories (filter #(not= (:name %) category-name) (:categories db))
;;          row-index-to-open (when (or (not= index row-index-currently-open)
;;                                   (nil? row-index-currently-open))
;;                           index)]
;;      (when (some? row-index-currently-open)
;;        (disable-editor row-index-currently-open))
;;      (when (some? row-index-to-open)
;;        (enable-editor row-index-to-open 0 category-name builder-category))
;;      (-> db
;;          (assoc :open-category-row row-index-to-open)
;;         ;;  (assoc :builder-category edit-category)
;;         ;;  (assoc :categories updated-categories)
;;          ))))

(defn add-old-name-to-category [category]
  (assoc category :old-name (:name category)))

(reg-event-db
 :edit-category3
 (fn
   [db [_ category-id index]]
   (println "edit-category3: " category-id)
   (let [current-builder-category (:builder-category db)
         new-category {:id "new-id" :name "" :marker {:value ""}}
         categories (conj (:categories db) new-category)
         new-builder-category (when (not= category-id (-> current-builder-category :id str))
                                (->>  categories
                                      (some #(when (= category-id (-> % :id str)) %))
                                      ;; (add-old-name-to-category)
                                      ))]
     (-> db
         (assoc :builder-category new-builder-category)))))

(reg-event-fx
 :request-all-transactions
 (fn
   [{db :db} _]
   {:http-xhrio {:method          :get
                 :uri             "http://localhost/transactions"
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:process-response]
                 :on-failure      [:bad-response]}
    :db  (assoc db :loading "true")}))

(reg-event-fx
 :request-all-categories
 (fn
   [{db :db} _]
   (println "requesting categories")
   {:http-xhrio {:method          :get
                 :uri             "http://localhost/categories"
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:get-categories-response]
                 :on-failure      [:bad-response]}
    :db  (assoc db :loading "true")}))


(reg-event-fx
 :toggle-chart
 (fn
   [{db :db} _]
   (let [display-option (-> db :displayed-transactions-data :display-option)
        ;;  _ (chart/draw-stacked-barchart (:displayed-transactions db) (:categories db)) 
        ;;  series (make-series data :category-name :month)
         new-display-option (if (= display-option :table)
                              :bar-chart
                              :table)
         period (:period db)]
    ;;  (.log js/console (goog.object/get color "ting"))
    ;;  (routes/navigate-to-parameters :display nil new-display-option nil)
     (dispatch [:navigate [period new-display-option nil]])
    ;;  db ; this might overwrite the changes made by the navigate dispatch over
    ;;  (assoc-in db [:displayed-transactions-data :display-option] new-display-option)
     )))