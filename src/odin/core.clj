(ns odin.core
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [compojure.core :as cpj]
            [odin.db2 :as db2]
            [odin.services.config-service :as config]
            [odin.services.auth-service :as auth]
            [odin.services.user-service :as user]
            [odin.services.account-service :as account]
            [odin.services.transaction-service :as transaction]
            [odin.services.category-service :as category]
            [odin.services.report-service :as report]
            [odin.services.tag-service :as tag]
            [odin.services.filter-service :as filter-svc]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body]]
            [ring.middleware.cors :refer [wrap-cors]]))


(defn wrap-auth
  "Middleware that verifies JWT from Authorization header and injects :user-id."
  [handler]
  (fn [request]
    (let [auth-header (get-in request [:headers "authorization"])
          token (when (and auth-header (s/starts-with? auth-header "Bearer "))
                  (subs auth-header 7))
          claims (when token (user/verify-jwt token))]
      (if claims
        (handler (assoc request :user-id (:user-id claims)))
        {:status 401
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error "Unauthorized"})}))))

(defn bank-oauth-callback-handler [request]
  (let [auth-data (auth/extract_authenticate_data request)]
    (if auth-data
      (let [{code :code state :state} auth-data
            redirect-uri (config/redirect-uri "/auth/bank/callback")
            _ (println "=== OAUTH CALLBACK === state/account-id:" state)
            tokens (auth/make_tokens code state redirect-uri)]
        (reset! auth/token_response_atom tokens)
        (when state
          (try
            (let [all-accounts (->> (db2/list-items "Account")
                                    (filter #(= state (get-in % [:AccountId :S]))))
                  match (first all-accounts)]
              (println "Found" (count all-accounts) "matching accounts for state:" state)
              (if match
                (let [user-id (get-in match [:UserId :S])
                      token-json (json/write-str tokens)]
                  (db2/update-account-tokens user-id state token-json)
                  (println "Stored tokens for account" state "user" user-id))
                (println "WARNING: No account found matching state" state "- tokens NOT stored in DB")))
            (catch Exception e
              (println "Could not store tokens for account" state (.getMessage e)))))
        {:status 302
         :headers {"Location" (str config/cors-origin)}
         :body ""})
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "No auth data received"})))

;; Public routes (no auth required)
(cpj/defroutes public-routes
  (cpj/POST "/auth/register" params user/register-handler)
  (cpj/POST "/auth/login" params user/login-handler)
  (cpj/GET "/auth/bank/callback" params bank-oauth-callback-handler))

;; Protected routes (auth required)
(cpj/defroutes protected-routes
  (cpj/GET "/auth/me" params user/me-handler)
  (cpj/GET "/transactions/:id/details" [id] (partial transaction/transaction_details_handler id))
  (cpj/GET "/transactions" params transaction/transaction_handler)
  (cpj/GET "/categories" params category/categories-handler)
  (cpj/POST "/category" params category/store-category-handler)
  (cpj/DELETE "/category/:id" [id] (partial category/delete-category-handler id))
  (cpj/POST "/transactions/update" params category/update-transactions-with-categories)
  (cpj/GET "/reports" params report/reports-handler)
  (cpj/POST "/report" params report/store-report-handler)
  (cpj/DELETE "/report/:id" [id] (partial report/delete-report-handler id))
  (cpj/POST "/migrate/markers-to-filters" params category/migrate-handler)
  (cpj/GET "/tags" params tag/tags-handler)
  (cpj/POST "/tag" params tag/store-tag-handler)
  (cpj/DELETE "/tag/:id" [id] (partial tag/delete-tag-handler id))
  (cpj/GET "/filters" params filter-svc/filters-handler)
  (cpj/POST "/filter" params filter-svc/store-filter-handler)
  (cpj/POST "/filters" params filter-svc/store-filters-handler)
  (cpj/DELETE "/filter/:id" [id] (partial filter-svc/delete-filter-handler id))
  (cpj/GET "/accounts" params account/accounts-handler)
  (cpj/POST "/account/connect" params account/connect-handler)
  (cpj/DELETE "/account/:id" [id] (partial account/delete-account-handler id))
  (cpj/DELETE "/user" params user/delete-user-handler))

(defn app [request]
  (let [uri (:uri request)]
    (if (or (s/starts-with? uri "/auth/register")
            (s/starts-with? uri "/auth/login")
            (s/starts-with? uri "/auth/bank/callback"))
      (public-routes request)
      ((wrap-auth protected-routes) request))))

(def app-handler
  (-> app
      (wrap-json-body {:key-fn keyword})
      (wrap-cors :access-control-allow-origin [#"http://localhost:4000"]
                 :access-control-allow-methods [:get :put :post :delete]
                 :access-control-allow-headers ["Origin" "X-Requested-With" "Content-Type" "Accept" "Authorization"])))

(defn run_server []
  (jetty/run-jetty app-handler
                   {:port 8080
                    :join? true}))

(defn -main [& args]
  (.start (Thread. run_server))
  (println "Odin server started on port 8080"))
