(ns client.events
  (:require [ajax.core :as ajax]
            [day8.re-frame.http-fx]
            [client.api :as api]
            [client.db :refer [default-db]]
            [client.events.period-selector :as period-selector]
            [client.events.displayed-transactions-viewer]
            [client.events.utils :as utils]
            [client.services.date-service :as date]
            [client.services.color-service :as color]
            [re-frame.core :refer [reg-event-db reg-event-fx after dispatch]]
            [clojure.string :as s]
            [goog.object :as g]
            [common.category-service :as category]
            [goog.string :as gstring]
            [client.routes :as routes]))


(reg-event-db
 :initialise-db
 (fn [_ _]
   (let [db default-db
         display-option (-> db :displayed-transactions-data :display-option)]
     (routes/start! (:period db) display-option)
     (let [token (api/get-token)]
       (if token
         (assoc db :auth {:token token :user nil :loading? true :error nil})
         db)))))

;;
;; Auth events
;;

(reg-event-fx
 :check-auth
 (fn [{db :db} _]
   (let [token (api/get-token)]
     (if token
       {:http-xhrio {:method          :get
                     :uri             (api/uri "/auth/me")
                     :headers         (api/auth-header)
                     :response-format (ajax/json-response-format {:keywords? true})
                     :on-success      [:check-auth-success]
                     :on-failure      [:check-auth-failure]}
        :db (assoc-in db [:auth :loading?] true)}
       {:db db}))))

(reg-event-fx
 :check-auth-success
 (fn [{db :db} [_ response]]
   (let [user (js->clj response)]
     {:db (assoc db :auth {:token (api/get-token) :user user :loading? false :error nil})
      :dispatch-n [[:request-all-transactions]
                   [:request-all-categories]
                   [:request-all-reports]
                   [:request-all-tags]
                   [:request-accounts]]})))

(reg-event-db
 :check-auth-failure
 (fn [db _]
   (api/remove-token!)
   (assoc db :auth {:token nil :user nil :loading? false :error nil})))

(reg-event-fx
 :login
 (fn [{db :db} [_ email password]]
   {:http-xhrio {:method          :post
                 :uri             (api/uri "/auth/login")
                 :params          {:email email :password password}
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:login-success]
                 :on-failure      [:login-failure]}
    :db (assoc db :auth {:token nil :user nil :loading? true :error nil})}))

(reg-event-fx
 :login-success
 (fn [{db :db} [_ response]]
   (let [result (js->clj response)
         token (:token result)]
     (api/set-token! token)
     {:db (assoc db :auth {:token token
                           :user {:user-id (:user-id result) :email (:email result)}
                           :loading? false
                           :error nil})
      :dispatch-n [[:request-all-transactions]
                   [:request-all-categories]
                   [:request-all-reports]
                   [:request-all-tags]
                   [:request-accounts]]})))

(reg-event-db
 :login-failure
 (fn [db [_ response]]
   (let [error-body (some-> response :response :error)]
     (assoc db :auth {:token nil :user nil :loading? false
                      :error (or error-body "Login failed")}))))

(reg-event-fx
 :register
 (fn [{db :db} [_ email password]]
   {:http-xhrio {:method          :post
                 :uri             (api/uri "/auth/register")
                 :params          {:email email :password password}
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:register-success]
                 :on-failure      [:register-failure]}
    :db (assoc db :auth {:token nil :user nil :loading? true :error nil})}))

(reg-event-fx
 :register-success
 (fn [{db :db} [_ response]]
   (let [result (js->clj response)
         token (:token result)]
     (api/set-token! token)
     {:db (assoc db :auth {:token token
                           :user {:user-id (:user-id result) :email (:email result)}
                           :loading? false
                           :error nil})})))

(reg-event-db
 :register-failure
 (fn [db [_ response]]
   (let [error-body (some-> response :response :error)]
     (assoc db :auth {:token nil :user nil :loading? false
                      :error (or error-body "Registration failed")}))))

(reg-event-db
 :logout
 (fn [db _]
   (api/remove-token!)
   (assoc db
          :auth {:token nil :user nil :loading? false :error nil}
          :all-transactions nil
          :categories []
          :reports []
          :tags []
          :accounts [])))

(reg-event-db
 :set-auth-view
 (fn [db [_ view]]
   (assoc db :auth-view view)))

(reg-event-fx
 :delete-user
 (fn [{db :db} _]
   {:http-xhrio {:method          :delete
                 :uri             (api/uri "/user")
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:delete-user-success]
                 :on-failure      [:delete-user-failure]}
    :db (assoc-in db [:auth :loading?] true)}))

(reg-event-db
 :delete-user-success
 (fn [db _]
   (api/remove-token!)
   (assoc db
          :auth {:token nil :user nil :loading? false :error nil}
          :all-transactions nil
          :categories []
          :reports []
          :tags []
          :accounts [])))

(reg-event-db
 :delete-user-failure
 (fn [db [_ error]]
   (println "Failed to delete user:" error)
   (assoc-in db [:auth :loading?] false)))

;;
;; Account events
;;

(reg-event-fx
 :request-accounts
 (fn [{db :db} _]
   {:http-xhrio {:method          :get
                 :uri             (api/uri "/accounts")
                 :headers         (api/auth-header)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:accounts-response]
                 :on-failure      [:accounts-failure]}}))

(reg-event-db
 :accounts-response
 (fn [db [_ response]]
   (assoc db :accounts (js->clj response))))

(reg-event-db
 :accounts-failure
 (fn [db [_ error]]
   (println "Failed to fetch accounts:" error)
   db))

(reg-event-fx
 :connect-account
 (fn [_ [_ provider account-name]]
   {:http-xhrio {:method          :post
                 :uri             (api/uri "/account/connect")
                 :params          {:provider provider :account-name account-name}
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:connect-account-success]
                 :on-failure      [:connect-account-failure]}}))

(reg-event-fx
 :connect-account-success
 (fn [_ [_ response]]
   (let [result (js->clj response)
         oauth-url (:oauth-url result)]
     (when oauth-url
       (.assign js/window.location oauth-url))
     {:dispatch [:request-accounts]})))

(reg-event-db
 :connect-account-failure
 (fn [db [_ error]]
   (println "Failed to connect account:" error)
   db))

(reg-event-fx
 :delete-account
 (fn [_ [_ account-id]]
   {:http-xhrio {:method          :delete
                 :uri             (api/uri-with-id "/account/" account-id)
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:delete-account-success]
                 :on-failure      [:delete-account-failure]}}))

(reg-event-fx
 :delete-account-success
 (fn [_ _]
   {:dispatch [:request-accounts]}))

(reg-event-db
 :delete-account-failure
 (fn [db [_ error]]
   (println "Failed to delete account:" error)
   db))

(defn- dedup-filters [filters]
  (->> filters
       (group-by :text)
       vals
       (mapv (fn [group]
               (or (first (filter #(seq (:tag-ids %)) group))
                   (first group))))
       (sort-by :order-index)
       vec))

(defn- rebuild-marker-from-filters [category]
  (let [filters (:filters category)
        marker-lines (-> category :marker :description)]
    (cond
      (seq filters)
      (let [deduped (dedup-filters filters)
            reindexed (vec (map-indexed (fn [idx f] (assoc f :order-index idx)) deduped))
            lines (mapv :text reindexed)
            value (clojure.string/join "\n" lines)]
        (-> category
            (assoc :filters reindexed)
            (assoc :marker {:description lines :value value})))

      (seq marker-lines)
      (let [synthetic-filters (vec (map-indexed
                                    (fn [idx text]
                                      {:text text :order-index idx :tag-ids []})
                                    marker-lines))]
        (assoc category :filters synthetic-filters))

      :else category)))

(reg-event-fx
 :get-categories-response
 (fn
   [{db :db} [_ response]]
   (let [raw-categories (js->clj response)
         categories (mapv rebuild-marker-from-filters raw-categories)
         period-transactions (:period-transactions db)
         builder-id (some-> db :builder-category :id str)
         updated-builder (when builder-id
                           (some #(when (= (str (:id %)) builder-id) %) categories))
         reapply? (:reapply-tags-pending db)]
     (cond-> {:db (cond-> db
                    true (assoc :loading "done")
                    true (assoc :categories categories)
                    period-transactions (assoc :summed-categories
                                              (utils/sum-categoires categories period-transactions))
                    updated-builder (assoc :builder-category updated-builder)
                    reapply? (dissoc :reapply-tags-pending))}
       reapply? (assoc :dispatch [:reapply-filter-tags])))))

(reg-event-db
 :mark-one-transaction-success
 (fn [db _] db))

(reg-event-db
 :category-stored-in-db-failure
 (fn
   [db [_ response]]
   (println "store-categories-failure" response)
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
 :bad-response
 (fn
   [db [_ response]]           ;; destructure the response from the event vector
   (println "retreiving all transactions failed: " response)
 db))

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

(reg-event-db
 :update-builder-category-target
 (fn
   [db [_ text]]
   (-> db
       (assoc-in [:builder-category :target] text))))

(reg-event-db
 :update-builder-category-bucket
 (fn
   [db [_ bucket]]
   (-> db
       (assoc-in [:builder-category :bucket] bucket))))

(reg-event-db
 :set-active-menu
 (fn [db [_ menu-key]]
   (assoc db :active-menu menu-key)))

(defn- update-target-in-seq [categories category-id target-str]
  (mapv (fn [cat]
          (if (= (:id cat) category-id)
            (assoc cat :target target-str)
            cat))
        categories))

(reg-event-db
 :update-category-target
 (fn [db [_ category-id target-str]]
   (-> db
       (update :categories update-target-in-seq category-id target-str)
       (update :summed-categories
               (fn [cats]
                 (mapv (fn [cat]
                         (if (= (:id cat) category-id)
                           (assoc cat :target target-str)
                           cat))
                       cats))))))

(reg-event-fx
 :save-category-target
 (fn [{db :db} [_ category-id]]
   (let [category (some #(when (= (:id %) category-id) %) (:categories db))
         target-str (str (:target category))
         target-num (when (and (seq target-str) (not= target-str ""))
                      (js/parseFloat target-str))
         save-category (if (and target-num (not (js/isNaN target-num)))
                         (assoc category :target target-num)
                         (dissoc category :target))]
     (when category
       {:http-xhrio {:method          :post
                     :uri             (api/uri "/category")
                     :params          (clj->js save-category)
                     :headers         (api/auth-header)
                     :format          (ajax/json-request-format)
                     :response-format (ajax/json-response-format {:keywords? true})
                     :on-success      [:target-saved-response]
                     :on-failure      [:category-stored-in-db-failure]}}))))

(reg-event-db
 :target-saved-response
 (fn [db [_ response]]
   (println "target-saved-response:" response)
   db))

(defn is-valid-color? [text]
  (and
   (or (-> text count (= 4))
       (-> text count (= 7)))
   (nil? (re-find #"[^0-9a-fA-F#]" text))))

(reg-event-db
 :update-builder-category-color
 (fn
   [db [_ text]]
   (println "update-builder-category-color: " text)
   (if (is-valid-color? text)
     (let [period-transactions (:period-transactions db)
           builder-category (-> (:builder-category db)
                                (assoc :color text)
                                (assoc :color-value text))
           marked-transactions (:updated-seq (category/add-category2 period-transactions builder-category))]
       (-> db
           (assoc :builder-category builder-category)
           (assoc-in [:displayed-transactions-data :displayed-transactions] marked-transactions)))
     (-> db
         (assoc-in [:builder-category :color-value] text)))))

(reg-event-db
 :mark-transactions
 (fn
   [db [_ raw-text]]
   (let [text (utils/sanitize-input raw-text)
         transactions (:period-transactions db)
         builder-category (-> (:builder-category db)
                              (assoc :marker (category/update-marker text)))
         marked-transactions (category/add-category transactions builder-category)
         tx-editor (:transaction-row-editor db)
         lines (-> builder-category :marker :description)
         edit-idx (:editing-filter-index tx-editor)
         updated-db (-> db
                        (assoc :builder-category builder-category)
                        (assoc-in [:displayed-transactions-data :displayed-transactions] marked-transactions))]
     (if tx-editor
       (let [synced-filter (when (and edit-idx (< edit-idx (count lines)))
                             (nth lines edit-idx))]
         (cond-> updated-db
           synced-filter (assoc-in [:transaction-row-editor :new-sub-filter] synced-filter)))
       updated-db))))

(reg-event-db
 :toggle-is-transaction-category-filtered
 (fn
   [db [_ is-checked?]]
   
   (let [
        ;;  _ (println "toggle-is-transaction-category-filtered: " is-checked?)
   ]
    ;;  (-> db
    ;;      (assoc-in [:transaction-row-editor :new-sub-filter] sub-filter)
    ;;      (assoc-in [:transaction-row-editor :is-match?] is-match?)\
     (-> db
         (assoc-in [:transaction-row-editor :filter-checked?] is-checked?)))
   ))

(reg-event-db
 :toggle-categorized-transactions
 (fn [db _]
   (update-in db [:transaction-row-editor :show-categorized-transactions?] not)))

(reg-event-db
 :toggle-uncategorized-transactions
 (fn [db _]
   (update-in db [:transaction-row-editor :show-uncategorized-transactions?] not)))

(reg-event-db
 :select-new-category
 (fn
   [db [_ new-category-change]]
   (println "select-new-category: " new-category-change)
   (let [transaction-row-editor (:transaction-row-editor db)
         no-category? (< (count new-category-change) 2)
         transaction-row-editor-update (-> (if no-category?
                                             (assoc transaction-row-editor :new-category nil)
                                             (assoc transaction-row-editor :new-category new-category-change))
                                           (dissoc :editing-filter-index))
         selected-cat (when-not no-category?
                        (some #(when (= (:id %) new-category-change) %)
                              (:categories db)))]
     (-> db
         (assoc :transaction-row-editor transaction-row-editor-update)
         (assoc :builder-category selected-cat)))))

(defn update-categories [categories builder-category]
  (let [match-index (some (fn [[index category]] (when (= (:id category) (:id builder-category)) index))
                          (map-indexed vector categories))]
    ;; (println "update-categories: " categories (count categories))
    ;; (println "update-categories: " (map :name categories))
    ;; (println "update-categories: " match-index)
    ;; (println "update-categories: " builder-category)
    (if (some? match-index)
      (assoc categories match-index builder-category)
      (conj categories builder-category))))

(defn update-category [db category]
  (let [builder-cat (:builder-category db)
        updated-category (or builder-cat
                             (let [new-sub-filter (-> db :transaction-row-editor :new-sub-filter)]
                               (-> category
                                   (update-in [:marker :description] #(conj % new-sub-filter))
                                   (update-in [:marker :value] #(str % "\n" new-sub-filter)))))
        category-to-save (dissoc updated-category :filters)]
     {:http-xhrio {:method          :post
                   :uri             (api/uri "/category")
                   :params          (clj->js category-to-save)
                   :headers         (api/auth-header)
                   :format          (ajax/json-request-format)
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:stored-category-response]
                   :on-failure      [:category-stored-in-db-failure]}
      :db (-> db
              (dissoc :transaction-row-editor))}))

(defn mark-one-transaction [db category transaction]
  (let [updated-transaction (assoc transaction :category-id (:id category))
        same-transaction? (fn [t] (and (= (:date updated-transaction) (:date t))
                                       (= (:amount updated-transaction) (:amount t))
                                       (= (:date-index updated-transaction) (:date-index t))))
        updated-all-transactions (mapv #(if (same-transaction? %) updated-transaction %) (:all-transactions db))
        updated-period-transactions (mapv #(if (same-transaction? %) updated-transaction %) (:period-transactions db))
        categories (:categories db)
        summed-categories (utils/sum-categoires categories updated-period-transactions)
        updated-displayed (mapv #(if (same-transaction? %) updated-transaction %)
                                (-> db :displayed-transactions-data :displayed-transactions))]
    {:http-xhrio {:method          :post
                  :uri             (api/uri "/transactions/update")
                  :params          (clj->js [updated-transaction])
                  :headers         (api/auth-header)
                  :format          (ajax/json-request-format)
                  :response-format (ajax/json-response-format {:keywords? true})
                  :on-success      [:mark-one-transaction-success]
                  :on-failure      [:category-stored-in-db-failure]}
     :db (-> db
             (assoc :all-transactions updated-all-transactions)
             (assoc :period-transactions updated-period-transactions)
             (assoc :summed-categories summed-categories)
             (assoc-in [:displayed-transactions-data :displayed-transactions] updated-displayed)
             (dissoc :transaction-row-editor)
             (dissoc :builder-category))}))

(reg-event-fx
 :update-transactions-step-one
 (fn
   [{db :db} [_ category transaction]]
   (println "update-transactions-step-one" category)
   (let [tx-editor (:transaction-row-editor db)
         filter-checked? (if (contains? tx-editor :filter-checked?)
                           (:filter-checked? tx-editor)
                           (:marked-by-filter? transaction))]
    (if filter-checked?
      (update-category db category)
      (mark-one-transaction db category transaction)))))

;; (reg-event-fx
;;  :update-transactions
;;  (fn
;;    [{db :db} [_ category]]
;;    (let [all-transactions (:all-transactions db)
;;          period-transactions (:period-transactions db)
;;          _ (println "stored-category-response stored-category: " category)
;;          accumulator (category/add-category2 all-transactions category)
;;          updated-all-transactions (:updated-seq accumulator)
;;          updated-period-transactions (:updated-seq (category/add-category2 period-transactions category))
;;          updated-categories (update-categories (:categories db) category)
;;          summed-categories (utils/sum-categoires updated-categories updated-period-transactions)
;;          updated-db (-> db
;;                         (assoc :categories updated-categories)
;;                         (assoc :summed-categories summed-categories)
;;                         (assoc :builder-category nil)
;;                         (assoc :all-transactions updated-all-transactions)
;;                         (assoc :period-transactions updated-period-transactions)
;;                         (assoc-in [:displayed-transactions-data :displayed-transactions] updated-period-transactions)
;;                         )]
;;      {:http-xhrio {:method          :post
;;                    :uri             (api/uri "/transactions/update")
;;                    :params          (clj->js (:only-full-updates accumulator))
;;                    :format          (ajax/json-request-format)
;;                    :response-format (ajax/json-response-format {:keywords? true})
;;                    :on-success      [:view-category-period [nil nil nil nil]]
;;                    :on-failure      [:category-stored-in-db-failure]}
;;       :db db})))

(reg-event-fx
 :store-category3
 (fn
   [{db :db} _]
   (println "store-category3")
   (let [raw-category (if (-> db :builder-category :id (= "new-id"))
                        (-> db :builder-category (dissoc :id))
                        (:builder-category db))
         target-str (:target raw-category)
         target-num (when (and target-str (not= (str target-str) ""))
                      (js/parseFloat (str target-str)))
         builder-category (if (and target-num (not (js/isNaN target-num)))
                            (assoc raw-category :target target-num)
                            (dissoc raw-category :target))
         category-to-save (dissoc builder-category :filters)]
     {:http-xhrio {:method          :post
                   :uri             (api/uri "/category")
                   :params          (clj->js category-to-save)
                   :headers         (api/auth-header)
                   :format          (ajax/json-request-format)
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:stored-category-response]
                   :on-failure      [:category-stored-in-db-failure]}
      :db (dissoc db :transaction-row-editor)})))

(reg-event-fx
 :stored-category-response
 (fn
   [{db :db
     [_ response] :event} _]
   (println "stored-category-response: " response)
   (let [stored-category (js->clj response)
         builder-cat (:builder-category db)
         category-id (:id stored-category)
         existing-cat-filters (some (fn [c]
                                      (when (= (str (:id c)) (str category-id))
                                        (:filters c)))
                                    (:categories db))
         builder-filters (if builder-cat
                           (vec (:filters builder-cat))
                           (let [marker-lines (or (-> stored-category :marker :description) [])
                                 existing-by-text (into {} (map (juxt :text identity)
                                                                (or existing-cat-filters [])))]
                             (vec (map-indexed
                                   (fn [idx line]
                                     (if-let [existing (get existing-by-text line)]
                                       (assoc existing :order-index idx)
                                       {:text line :order-index idx :tag-ids []}))
                                   marker-lines))))
         filters-to-save (mapv (fn [f]
                                 (merge {:category-id category-id
                                         :text (or (:text f) "")
                                         :order-index (or (:order-index f) 0)
                                         :tag-ids (or (:tag-ids f) [])}
                                        (when (:id f) {:id (:id f)})))
                               builder-filters)
         all-transactions (:all-transactions db)
         period-transactions (:period-transactions db)
         category-with-filters (assoc stored-category :filters builder-filters)
         accumulator (category/add-category2 all-transactions category-with-filters)
         updated-all-transactions (:updated-seq accumulator)
         updated-period-transactions (:updated-seq (category/add-category2 period-transactions category-with-filters))
         updated-categories (update-categories (:categories db) (rebuild-marker-from-filters
                                                                  (assoc stored-category :filters builder-filters)))
         summed-categories (utils/sum-categoires updated-categories updated-period-transactions)
         updated-db (-> db
                        (assoc :categories updated-categories)
                        (assoc :summed-categories summed-categories)
                        (assoc :builder-category nil)
                        (dissoc :transaction-row-editor)
                        (assoc :all-transactions updated-all-transactions)
                        (assoc :period-transactions updated-period-transactions)
                        (assoc-in [:displayed-transactions-data :displayed-transactions] updated-period-transactions))]
     (println "store-categories-success")
     (cond-> {:http-xhrio {:method          :post
                            :uri             (api/uri "/transactions/update")
                            :params          (clj->js (:only-full-updates accumulator))
                            :headers         (api/auth-header)
                            :format          (ajax/json-request-format)
                            :response-format (ajax/json-response-format {:keywords? true})
                            :on-success      [:view-category-period [nil nil nil nil]]
                            :on-failure      [:category-stored-in-db-failure]}
              :db updated-db}
       (seq filters-to-save)
       (assoc :dispatch [:save-category-filters (vec filters-to-save)])))))

(reg-event-fx
 :delete-category
 (fn
   [{db :db
     [_ category-id] :event} _]
   (let [all-transactions (:all-transactions db)
         ; delete the category from db.categories also
         updated-categories (->> db :categories (filter #(not= (:id %) category-id)) (into []))
         {updated-transactions :updates
          updated-all-transactions :all} (category/delete-category all-transactions category-id)]
     {:http-xhrio {:method          :delete
                   :uri             (api/uri-with-id "/category/" category-id)
                   :params          (clj->js updated-transactions)
                   :headers         (api/auth-header)
                   :format          (ajax/json-request-format)
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:view-category-period [updated-all-transactions nil nil nil]]
                   :on-failure      [:category-stored-in-db-failure]}
      :db (-> db
              (assoc :categories updated-categories)
              (assoc :all-transactions updated-all-transactions))})))

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
     (dispatch [:navigate [nil :table filter-path]]))))

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
         new-category {:id "new-id" :name "" :color "#5ce67e" :marker {:value ""}}
         categories (conj (:categories db) new-category)
         new-builder-category (when (not= category-id (-> current-builder-category :id str))
                                (->>  categories
                                      (some #(when (= category-id (-> % :id str)) %))
                                      ;; (add-old-name-to-category)
                                      ))]
     (-> db
         (assoc :builder-category new-builder-category)))))

(reg-event-db
 :edit-sidebar-category
 (fn [db [_ category-id]]
   (let [current-id (-> db :builder-category :id str)
         new-builder (when (not= (str category-id) current-id)
                       (if (= category-id "new-id")
                         {:id "new-id" :name "" :color "#5ce67e" :marker {:value "" :description []}}
                         (some #(when (= (str (:id %)) (str category-id)) %)
                               (:categories db))))]
     (assoc db :builder-category new-builder))))

(reg-event-db
 :edit-transaction-row
 (fn
   [db [_ row-index]]
   (if (and (some? (:transaction-row-editor db))
            (= row-index (-> db :transaction-row-editor :row-index)))
     (-> db
         (dissoc :transaction-row-editor)
         (dissoc :builder-category))
     (let [txn (get (-> db :displayed-transactions-data :displayed-transactions) row-index)
           cat-id (:category-id txn)
           cat (when cat-id
                 (some #(when (= (:id %) cat-id) %) (:categories db)))
           filter-idx (when cat
                        (let [lines (-> cat :marker :description)
                              desc (:description txn)]
                          (when (and lines desc)
                            (some (fn [[i line]]
                                    (when (category/match-fun desc line) i))
                                  (map-indexed vector lines)))))]
       (-> db
           (assoc :transaction-row-editor
                  (cond-> {:row-index row-index}
                    filter-idx (assoc :editing-filter-index filter-idx)))
           (assoc :builder-category cat))))))

(reg-event-fx
 :request-all-transactions
 (fn
   [{db :db} _]
   {:http-xhrio {:method          :get
                 :uri             (api/uri "/transactions")
                 :headers         (api/auth-header)
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
                 :uri             (api/uri "/categories")
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:get-categories-response]
                 :on-failure      [:bad-response]}
    :db  (assoc db :loading "true")}))

;;
;; Tags
;;

(reg-event-fx
 :request-all-tags
 (fn [_ _]
   {:http-xhrio {:method          :get
                 :uri             (api/uri "/tags")
                 :headers         (api/auth-header)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:get-tags-response]
                 :on-failure      [:get-tags-failure]}}))

(reg-event-db
 :get-tags-response
 (fn [db [_ response]]
   (assoc db :tags (js->clj response))))

(reg-event-db
 :get-tags-failure
 (fn [db [_ error]]
   (println "Failed to fetch tags:" error)
   db))

(reg-event-fx
 :store-tag
 (fn [_ [_ tag]]
   {:http-xhrio {:method          :post
                 :uri             (api/uri "/tag")
                 :params          tag
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:store-tag-response]
                 :on-failure      [:store-tag-failure]}}))

(reg-event-fx
 :store-tag-response
 (fn [_ _]
   {:dispatch [:request-all-tags]}))

(reg-event-db
 :store-tag-failure
 (fn [db [_ error]]
   (println "Failed to store tag:" error)
   db))

(reg-event-fx
 :delete-tag
 (fn [_ [_ tag-id]]
   {:http-xhrio {:method          :delete
                 :uri             (api/uri-with-id "/tag/" tag-id)
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:delete-tag-response]
                 :on-failure      [:delete-tag-failure]}}))

(reg-event-fx
 :delete-tag-response
 (fn [_ _]
   {:dispatch [:request-all-tags]}))

(reg-event-db
 :delete-tag-failure
 (fn [db [_ error]]
   (println "Failed to delete tag:" error)
   db))

;;
;; Filters
;;

(reg-event-fx
 :store-filter
 (fn [_ [_ filter-item]]
   {:http-xhrio {:method          :post
                 :uri             (api/uri "/filter")
                 :params          filter-item
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:store-filter-response]
                 :on-failure      [:store-filter-failure]}}))

(reg-event-fx
 :store-filter-response
 (fn [_ _]
   {:dispatch [:request-all-categories]}))

(reg-event-db
 :store-filter-failure
 (fn [db [_ error]]
   (println "Failed to store filter:" error)
   db))

(reg-event-fx
 :delete-filter
 (fn [_ [_ filter-id]]
   {:http-xhrio {:method          :delete
                 :uri             (api/uri-with-id "/filter/" filter-id)
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:delete-filter-response]
                 :on-failure      [:delete-filter-failure]}}))

(reg-event-fx
 :delete-filter-response
 (fn [_ _]
   {:dispatch [:request-all-categories]}))

(reg-event-db
 :delete-filter-failure
 (fn [db [_ error]]
   (println "Failed to delete filter:" error)
   db))

(reg-event-fx
 :save-category-filters
 (fn [_ [_ filters]]
   {:http-xhrio {:method          :post
                 :uri             (api/uri "/filters")
                 :params          (clj->js filters)
                 :headers         (api/auth-header)
                 :format          (ajax/json-request-format)
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:save-category-filters-response]
                 :on-failure      [:save-category-filters-failure]}}))

(reg-event-fx
 :save-category-filters-response
 (fn [{db :db} _]
   (println "Filters saved successfully")
   {:db (assoc db :reapply-tags-pending true)
    :dispatch [:request-all-categories]}))

(reg-event-db
 :save-category-filters-failure
 (fn [db [_ error]]
   (println "Failed to save filters:" error)
   db))

(defn- recompute-filter-tags [categories txn]
  (let [old-filter-tags (set (or (:filter-tag-ids txn) []))
        manual-tags (vec (remove old-filter-tags (or (:tag-ids txn) [])))
        clean-txn (-> txn (assoc :tag-ids manual-tags) (dissoc :filter-tag-ids))]
    (category/apply-tags-from-filters categories clean-txn)))

(reg-event-fx
 :reapply-filter-tags
 (fn [{db :db} _]
   (let [categories (:categories db)
         all-txns (:all-transactions db)
         period-txns (:period-transactions db)
         reapply (partial recompute-filter-tags categories)
         updated-all (mapv reapply all-txns)
         updated-period (mapv reapply period-txns)
         changed (filterv (fn [[old new]]
                            (or (not= (:tag-ids old) (:tag-ids new))
                                (not= (:filter-tag-ids old) (:filter-tag-ids new))))
                          (map vector all-txns updated-all))
         to-persist (mapv second changed)]
     (println "Reapplying filter tags:" (count to-persist) "transactions changed")
     (cond-> {:db (-> db
                      (assoc :all-transactions updated-all)
                      (assoc :period-transactions updated-period)
                      (assoc-in [:displayed-transactions-data :displayed-transactions] updated-period))}
       (seq to-persist)
       (assoc :http-xhrio {:method          :post
                           :uri             (api/uri "/transactions/update")
                           :params          (clj->js to-persist)
                           :headers         (api/auth-header)
                           :format          (ajax/json-request-format)
                           :response-format (ajax/json-response-format {:keywords? true})
                           :on-success      [:reapply-filter-tags-success]
                           :on-failure      [:reapply-filter-tags-failure]})))))

(reg-event-db
 :reapply-filter-tags-success
 (fn [db _]
   (println "Filter tags reapplied and persisted successfully")
   db))

(reg-event-db
 :reapply-filter-tags-failure
 (fn [db [_ error]]
   (println "Failed to persist reapplied filter tags:" error)
   db))

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

(defn- update-transaction-in-list [transactions updated-txn]
  (mapv (fn [t]
          (if (and (= (:date t) (:date updated-txn))
                   (= (:amount t) (:amount updated-txn))
                   (= (:date-index t) (:date-index updated-txn)))
            updated-txn
            t))
        transactions))

(reg-event-fx
 :toggle-transaction-tag
 (fn [{db :db} [_ transaction-index tag-id]]
   (let [transactions (vec (-> db :displayed-transactions-data :displayed-transactions))
         transaction (get transactions transaction-index)
         current-tags (set (or (:tag-ids transaction) []))
         new-tags (if (contains? current-tags tag-id)
                    (vec (disj current-tags tag-id))
                    (vec (conj current-tags tag-id)))
         updated-transaction (assoc transaction :tag-ids new-tags)
         updated-displayed (assoc transactions transaction-index updated-transaction)
         updated-all (update-transaction-in-list (:all-transactions db) updated-transaction)
         updated-period (update-transaction-in-list (:period-transactions db) updated-transaction)]
     {:db (-> db
              (assoc-in [:displayed-transactions-data :displayed-transactions] updated-displayed)
              (assoc :all-transactions updated-all)
              (assoc :period-transactions updated-period))
      :http-xhrio {:method          :post
                   :uri             (api/uri "/transactions/update")
                   :params          (clj->js [updated-transaction])
                   :headers         (api/auth-header)
                   :format          (ajax/json-request-format)
                   :response-format (ajax/json-response-format {:keywords? true})
                   :on-success      [:toggle-transaction-tag-success]
                   :on-failure      [:toggle-transaction-tag-failure]}})))

(reg-event-db
 :toggle-transaction-tag-success
 (fn [db _] db))

(reg-event-db
 :toggle-transaction-tag-failure
 (fn [db [_ error]]
   (println "Failed to persist transaction tag:" error)
   db))
