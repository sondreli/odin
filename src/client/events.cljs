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
 :category-stored-in-db-success
 (fn
   [db [_ response]]
   (println "store-categories-success")
   db))

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

(reg-event-fx
 :store-category2
 (fn
   [{db :db} _]
   (let [builder-category (:builder-category db)
         all-transactions (:all-transactions db)
         period-transactions (:period-transactions db)
         accumulator (category/add-category2 all-transactions builder-category)
        ;;  updated-all-transactions (category/add-category all-transactions builder-category)
         updated-all-transactions (:updated-seq accumulator)
         updated-period-transactions (category/add-category period-transactions builder-category)
         updated-categories (-> (:categories db)
                                (conj builder-category))
         summed-categories (utils/sum-categoires updated-categories updated-period-transactions)
         updated-db (-> db
                        (assoc :categories updated-categories)
                        (assoc :summed-categories summed-categories)
                        (assoc :builder-category nil)
                        (assoc :all-transactions updated-all-transactions)
                        (assoc :period-transactions updated-period-transactions)
                        (assoc :displayed-transactions updated-period-transactions))]
     {:http-xhrio {:method          :post
                   :uri             "http://localhost/categories"
                   :params          (clj->js [{:category builder-category
                                               :updated-transactions (:only-updates accumulator)}])
                   :format          (ajax/json-request-format)
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:category-stored-in-db-success]
                   :on-failure      [:category-stored-in-db-failure]}
      :db  updated-db})
   ))

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

(defn add-textarea [tbody index]
  (let [row (. tbody insertRow (+ index 1))
        new-cell (. row insertCell 0)
        ;; _ (. new-cell setAttribute "colspan" "3")
        ;; _ (. row addEventListener "click" #(dispatch [:toggle-transaction-row index]))
        textarea (. js/document createElement "textarea")
        _ (g/set textarea "type" "text")
        _ (g/set textarea "rows" 5)
        _ (g/set textarea "style" "width: 100%")
        ]
    (. new-cell appendChild textarea)))

(defn create-option [text]
  (let [option (.createElement js/document "option")]
    (g/set option "value" text)
    (g/set option "text" text)
    option))

(defn enable-editor [index table-index-offset text]
  (let [tbody (. js/document getElementById "categories-tbody")

        name-input (. js/document createElement "input")
        _ (g/set name-input "type" "text")
        _ (g/set name-input "value" text)
        color-selector (.createElement js/document "select")
        options (->> ["blue" "green" "red"]
                     (map create-option)
                     (map #(.appendChild color-selector %))
                     doall)
        this-first-td (-> tbody .-rows (.item index) .-cells (.item 0))
        _ (g/set this-first-td "innerHTML" "")
        _ (.appendChild this-first-td name-input)
        _ (.appendChild this-first-td color-selector)

        _ (.log js/console tbody)
        _ (.log js/console this-first-td)
        ]
    (add-textarea tbody index)))

(defn disable-editor [index category-name]
  (let [tbody (. js/document getElementById "categories-tbody")
        textarea-row (. tbody deleteRow (+ index 1))
        first-td (-> tbody .-rows (.item index) .-cells (.item 0))
        _ (.log js/console first-td)
        _ (.removeChild first-td (.-lastChild first-td))
        _ (.removeChild first-td (.-lastChild first-td))
        _ (g/set first-td "innerHTML" category-name)
        ]))


(reg-event-db
 :edit-category2
 (fn
   [db [_ category-name index]]
   (println "edit-category2 " category-name index)
   (let [open-category-row (:open-category-row db)
         open-row-index (when (or (not= index open-category-row)
                                  (nil? open-category-row))
                          index)]
     (when (some? open-category-row)
       (disable-editor open-category-row category-name))
     (when (some? open-row-index)
       (enable-editor open-row-index 0 category-name))
     
     (assoc db :open-category-row open-row-index))))

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