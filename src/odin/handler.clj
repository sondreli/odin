(ns odin.handler
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [compojure.core :as cpj]
            [odin.db2 :as db2]
            [odin.services.config-service :as config]
            [odin.services.auth-service :as auth]
            [odin.services.user-service :as user]
            [odin.services.account-service :as account]
            [odin.services.nordnet-service :as nordnet]
            [odin.services.grocery-service :as grocery]
            [odin.services.coop-auth :as coop-auth]
            [odin.services.wealth-service :as wealth]
            [odin.services.price-service :as price]
            [odin.services.transaction-service :as transaction]
            [odin.services.category-service :as category]
            [odin.services.report-service :as report]
            [odin.services.tag-service :as tag]
            [odin.services.filter-service :as filter-svc]
            [odin.services.loan-service :as loan]
            [odin.services.pace-service :as pace]
            [ring.middleware.json :refer [wrap-json-body]]
            [ring.middleware.cors :refer [wrap-cors]]))

(defn wrap-auth
  "Middleware that verifies JWT from Authorization header and injects :user-id.
   OPTIONS (CORS preflight) is passed through without a token."
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status 200 :headers {} :body ""}
      (let [auth-header (get-in request [:headers "authorization"])
            token (when (and auth-header (s/starts-with? auth-header "Bearer "))
                    (subs auth-header 7))
            claims (when token (user/verify-jwt token))]
        (if claims
          (handler (assoc request :user-id (:user-id claims)))
          {:status 401
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:error "Unauthorized"})})))))

(defn wrap-not-found
  "Compojure returns nil for unmatched routes; a nil response skips CORS headers
   and the browser reports a CORS error instead of 404."
  [handler]
  (fn [request]
    (or (handler request)
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error "Not found"})})))

(defn wrap-exceptions
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (println "Unhandled handler error:" (.getMessage e))
        (.printStackTrace e)
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error (or (.getMessage e) "Internal server error")})}))))

(defn wrap-strip-prefix
  "Middleware that strips known path prefixes (stage name, /api) from URI."
  [handler]
  (fn [req]
    (let [uri (:uri req)
          ;; Strip /{stage}/api or /api prefix
          stripped (cond
                     ;; API Gateway direct: /{stage}/api/...
                     (re-find #"^/[^/]+/api(/.*)?$" uri)
                     (or (second (re-find #"^/[^/]+/api(/.*)$" uri)) "/")
                     ;; CloudFront: /api/...
                     (s/starts-with? uri "/api")
                     (let [s (subs uri 4)] (if (empty? s) "/" s))
                     ;; No prefix
                     :else uri)]
      (handler (assoc req :uri stripped)))))

(defn bank-oauth-callback-handler [request]
  (let [auth-data (auth/extract_authenticate_data request)]
    (if auth-data
      (let [{code :code state :state client_secret :client_secret} auth-data
            _ (println "=== OAUTH CALLBACK === state/account-id:" state "has client_secret:" (some? client_secret))]
        (if-not state
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:error "Missing state parameter"})}
          (try
            ;; Find the account by scanning (state = account-id)
            (let [all-accounts (->> (db2/list-items "Account")
                                    (filter #(= state (get-in % [:AccountId :S]))))
                  match (first all-accounts)]
              (if-not match
                (do (println "WARNING: No account found matching state" state)
                    {:status 400
                     :headers {"Content-Type" "application/json"}
                     :body (json/write-str {:error "Account not found"})})
                (let [user-id (get-in match [:UserId :S])
                      account (db2/get-account user-id state)
                      client-id (:client-id account)
                      secret (or client_secret (:client-secret account))
                      redirect-uri (:redirect-uri account)
                      tokens (auth/make_tokens code state redirect-uri client-id secret)
                      token-json (json/write-str tokens)]
                  ;; Store the client_secret from the callback if we got one
                  (when (and client_secret (not= client_secret (:client-secret account)))
                    (db2/update-account-client-secret user-id state client_secret))
                  (reset! auth/token_response_atom tokens)
                  (db2/update-account-tokens user-id state token-json)
                  (println "Stored tokens for account" state "user" user-id)
                  {:status 302
                   :headers {"Location" (str @config/cors-origin)}
                   :body ""})))
            (catch Exception e
              (println "OAuth callback error:" (.getMessage e))
              {:status 500
               :headers {"Content-Type" "application/json"}
               :body (json/write-str {:error "OAuth callback failed"})}))))
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "No auth data received"})))

;; Public routes (no auth required)
(cpj/defroutes public-routes
  (cpj/GET "/health" _ {:status 200 :body "ok"})
  (cpj/POST "/auth/register" params user/register-handler)
  (cpj/POST "/auth/login" params user/login-handler)
  (cpj/GET "/auth/bank/callback" params bank-oauth-callback-handler))

;; Protected routes (auth required)
(cpj/defroutes protected-routes
  (cpj/GET "/auth/me" params user/me-handler)
  (cpj/GET "/transactions/recent" params transaction/recent_transaction_handler)
  (cpj/GET "/transactions/:id/details" [id] (partial transaction/transaction_details_handler id))
  (cpj/GET "/transactions" params transaction/transaction_handler)
  (cpj/GET "/pace-prediction" params pace/prediction-handler)
  (cpj/GET "/investment-transactions" params transaction/investment-transactions-handler)
  (cpj/GET "/balance" params transaction/balance_handler)
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
  (cpj/GET "/loans" params loan/loans-handler)
  (cpj/POST "/loan" params loan/store-loan-handler)
  (cpj/DELETE "/loan/:id" [id] (partial loan/delete-loan-handler id))
  (cpj/GET "/accounts" params transaction/accounts-handler)
  (cpj/POST "/account/connect" params account/connect-handler)
  (cpj/POST "/account/:id/reauth" [id] (partial account/reauth-handler id))
  (cpj/POST "/account/coop/start" params coop-auth/start-handler)
  (cpj/POST "/account/:id/coop/complete" [id] (partial coop-auth/complete-handler id))
  (cpj/POST "/account/:id/import-csv" [id] (partial nordnet/import-csv-handler id))
  (cpj/POST "/account/:id/import-credit-csv" [id] (partial nordnet/import-credit-csv-handler id))
  (cpj/GET "/grocery/items" params grocery/items-handler)
  (cpj/POST "/grocery/sync" params grocery/sync-handler)
  (cpj/GET "/wealth" params wealth/wealth-handler)
  (cpj/GET "/leverage-settings" params wealth/leverage-settings-handler)
  (cpj/POST "/leverage-setting" params wealth/save-leverage-setting-handler)
  (cpj/POST "/prices/refresh" params price/refresh-handler)
  (cpj/DELETE "/account/:id" [id] (partial account/delete-account-handler id))
  (cpj/DELETE "/user" params user/delete-user-handler))

(defn app [request]
  (let [uri (:uri request)]
    (if (or (s/starts-with? uri "/auth/register")
            (s/starts-with? uri "/auth/login")
            (s/starts-with? uri "/auth/bank/callback")
            (= uri "/health"))
      (public-routes request)
      ((wrap-auth protected-routes) request))))

(def app-handler
  (delay
    (-> app
        wrap-not-found
        wrap-exceptions
        (wrap-json-body {:key-fn keyword})
        (wrap-cors :access-control-allow-origin [(re-pattern
                                                 (str "^"
                                                      (java.util.regex.Pattern/quote @config/cors-origin)
                                                      "$"))]
                   :access-control-allow-methods [:get :put :post :delete :options]
                   :access-control-allow-headers ["Origin" "X-Requested-With" "Content-Type" "Accept" "Authorization"])
        wrap-strip-prefix)))
