(ns odin.services.account-service
  (:require [odin.db2 :as db2]
            [clojure.data.json :as json]
            [clojure.string :as s])
  (:import [java.time Instant]))

(def providers
  {"sparebank1-ost" {:name "Sparebank1 Østlandet"
                     :type :oauth
                     :fin-inst "fid-ostlandet"
                     :auth-url "https://api-auth.sparebank1.no/oauth/authorize"
                     :token-url "https://api-auth.sparebank1.no/oauth/token"
                     :api-base "https://api.sparebank1.no/personal/banking"}
   "nordnet" {:name "Nordnet"
              ;; CSV providers have no API integration; transactions are uploaded as a CSV export.
              :type :csv}
   ;; Grocery loyalty apps — unofficial reverse-engineered APIs; credentials = username + bearer token.
   "trumf" {:name "Trumf" :type :grocery}
   "rema"  {:name "Rema 1000" :type :grocery}
   "coop"  {:name "Coop" :type :grocery}})

(defn get-provider [provider-key]
  (get providers provider-key))

(defn build-oauth-url [provider-key account-id client-id redirect-uri]
  (let [provider (get-provider provider-key)]
    (format "%s?client_id=%s&state=%s&redirect_uri=%s&finInst=%s&response_type=code"
            (:auth-url provider)
            client-id
            account-id
            redirect-uri
            (:fin-inst provider))))

(defn create-account [user-id provider-key account-name client-id client-secret redirect-uri]
  (let [account-id (str (java.util.UUID/randomUUID))
        now (str (Instant/now))
        account {:user-id user-id
                 :account-id account-id
                 :provider provider-key
                 :account-name (or account-name (:name (get-provider provider-key)))
                 :client-id client-id
                 :client-secret client-secret
                 :redirect-uri redirect-uri
                 :created-at now}]
    (db2/put-account account)
    account))

(defn has-credentials? [account]
  (let [td (when-let [raw (:token-data account)]
             (try (json/read-str raw :key-fn keyword) (catch Exception _ nil)))]
    (boolean (or (and (seq (:client-secret account))
                      (not (s/starts-with? (str (:client-secret account)) "{")))
                 (:access_token td)))))

(defn get-user-accounts [user-id]
  (let [accounts (db2/get-accounts-for-user user-id)]
    (mapv (fn [a]
            (-> a
                (assoc :has-credentials (has-credentials? a))
                (dissoc :token-data :client-secret :redirect-uri)))
          accounts)))

(defn get-account-tokens [user-id account-id]
  (when-let [account (db2/get-account user-id account-id)]
    (when-let [td (:token-data account)]
      (json/read-str td :key-fn keyword))))

(defn store-account-tokens [user-id account-id tokens]
  (let [token-json (json/write-str tokens)]
    (db2/update-account-tokens user-id account-id token-json)
    tokens))

(defn store-bank-account-key [user-id account-id bank-key]
  (db2/update-account-bank-key user-id account-id bank-key))

(defn store-account-number [user-id account-id account-number]
  (db2/update-account-number user-id account-id account-number))

(defn store-account-last-sync [user-id account-id last-sync]
  (db2/update-account-last-sync user-id account-id last-sync))

(defn get-account-with-tokens [user-id account-id]
  (db2/get-account user-id account-id))

;; Handlers

(defn connect-handler [request]
  (let [user-id (:user-id request)
        body (:body request)
        _ (println "connect-handler body:" (pr-str body))
        {:keys [provider account-name client-id client-secret redirect-uri]} body]
    (cond
      (not (get-provider provider))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (str "Unknown provider: " provider)})}

      ;; CSV providers (e.g. Nordnet) need no OAuth credentials; transactions are uploaded later.
      (= :csv (:type (get-provider provider)))
      (let [account (create-account user-id provider account-name nil nil nil)]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:account (dissoc account :client-secret)})})

      ;; Coop: start Auth0 login (token is retrieved after the user pastes the callback URL).
      (= "coop" provider)
      (try
        (let [result ((requiring-resolve 'odin.services.coop-auth/start-login!)
                      user-id {:account-name account-name})]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/write-str result)})
        (catch Exception e
          {:status 500
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:error (.getMessage e)})}))

      ;; Other grocery loyalty apps: store username + access token (no OAuth redirect).
      (= :grocery (:type (get-provider provider)))
      (if (empty? client-secret)
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error "Tilgangstoken er påkrevd"})}
        (let [account (create-account user-id provider account-name
                                      (when (seq client-id) client-id)
                                      client-secret nil)]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:account (dissoc account :client-secret)})}))

      (or (empty? client-id) (empty? client-secret) (empty? redirect-uri))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "client-id, client-secret, and redirect-uri are required"})}

      :else
      (let [account (create-account user-id provider account-name client-id client-secret redirect-uri)
            oauth-url (build-oauth-url provider (:account-id account) client-id redirect-uri)]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:account (dissoc account :client-secret) :oauth-url oauth-url})}))))

(defn accounts-handler [request]
  (let [user-id (:user-id request)
        accounts (get-user-accounts user-id)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str accounts)}))

(defn reauth-handler
  "Rebuild the OAuth authorize URL for an already-connected account so the user can
   re-authenticate after the bank refresh token expired (PSD2 SCA requires the user
   to log in with BankID again). Credentials are taken from the stored account, so
   the user does not re-enter client-id/secret."
  [account-id request]
  (let [user-id (:user-id request)
        account (db2/get-account user-id account-id)]
    (cond
      (nil? account)
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "Account not found"})}

      (not= :oauth (:type (get-provider (:provider account))))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "Account does not support OAuth re-authentication"})}

      :else
      (let [oauth-url (build-oauth-url (:provider account)
                                       account-id
                                       (:client-id account)
                                       (:redirect-uri account))]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:oauth-url oauth-url})}))))

(defn delete-account-handler [account-id request]
  (let [user-id (:user-id request)]
    (db2/delete-account user-id account-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:message "Account deleted"})}))
