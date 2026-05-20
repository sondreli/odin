(ns odin.services.account-service
  (:require [odin.db2 :as db2]
            [clojure.data.json :as json])
  (:import [java.time Instant]))

(def providers
  {"sparebank1-ost" {:name "Sparebank1 Østlandet"
                     :fin-inst "fid-ostlandet"
                     :auth-url "https://api-auth.sparebank1.no/oauth/authorize"
                     :token-url "https://api-auth.sparebank1.no/oauth/token"
                     :api-base "https://api.sparebank1.no/personal/banking"}})

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

(defn get-user-accounts [user-id]
  (let [accounts (db2/get-accounts-for-user user-id)]
    (mapv #(dissoc % :token-data :client-secret :redirect-uri) accounts)))

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

(defn delete-account-handler [account-id request]
  (let [user-id (:user-id request)]
    (db2/delete-account user-id account-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {:message "Account deleted"})}))
