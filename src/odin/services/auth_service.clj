(ns odin.services.auth-service
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [odin.services.http-service :as http]))

(def token_response_atom (atom {}))
(def token_response_delivered_promise (promise))
(add-watch token_response_atom :watch-changed
           (fn [_ _ old new]
             (when-not (= old new) (deliver token_response_delivered_promise :changed))))

(defn authenticate [client-id redirect-url state]
  (println "open in browser, authenticate and copy code from the return uri")
  (let [url (format "https://api-auth.sparebank1.no/oauth/authorize?client_id=%s&state=%s&redirect_uri=%s&finInst=fid-ostlandet&response_type=code"
                    client-id state redirect-url)]
    (println url)))

(defn extract_authenticate_data [req]
  (if (:query-string req)
    (let [key-value-strings (-> req
                              :query-string
                              (s/split #"&"))
          key-values (->> key-value-strings
                        (map #(s/split % #"="))
                        (map (fn [[k v]] [(keyword k) v]))
                        (into {}))]
        key-values)
    nil))

(defn make_token_request [code state redirect_uri client-id client-secret]
  (http/http-post-form "https://api-auth.sparebank1.no/oauth/token"
                       {:form-params (cond-> {:client_id client-id
                                              :code code
                                              :grant_type "authorization_code"
                                              :state state
                                              :redirect_uri redirect_uri}
                                       client-secret (assoc :client_secret client-secret))}))

(defn refresh_token_request [client-id client-secret {refresh_token :refresh_token}]
  (http/http-post-form "https://api-auth.sparebank1.no/oauth/token"
                       {:form-params (cond-> {:client_id client-id
                                              :refresh_token refresh_token
                                              :grant_type "refresh_token"}
                                       client-secret (assoc :client_secret client-secret))}))

(defn token_response2token [response]
  (let [body_string (:body response)
        tokens (json/read-str body_string :key-fn keyword)]
    tokens))

(defn add_token_expires_at [tokens]
  (let [expires_in (:expires_in tokens)
        unix_time_now (quot (System/currentTimeMillis) 1000)
        token_expires_at (+ unix_time_now expires_in)]
    (assoc tokens :token_expires_at token_expires_at)))

(defn store_tokens_to_file [tokens]
  (try
    (spit "session_tokens.txt" (with-out-str (pr tokens)))
    (catch Exception _))
  tokens)

(defn make_tokens [code state redirect_uri client-id client-secret]
  (let [token_response_json (make_token_request code state redirect_uri client-id client-secret)
        tokens (-> token_response_json
                   token_response2token
                   add_token_expires_at)]
    (store_tokens_to_file tokens)))

(defn refresh_tokens [client-id client-secret tokens]
  (println "refresh_tokens")
  (try
    (let [token_response_json (refresh_token_request client-id client-secret tokens)
          tokens (-> token_response_json
                     token_response2token
                     add_token_expires_at)]
      (println "refreshed tokens:")
      (pp/pprint tokens)
      (store_tokens_to_file tokens))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            body (try (json/read-str (:body data) :key-fn keyword) (catch Exception _ nil))]
        (if (and (= 400 (:status data)) body)
          (do
            (println "\n=== TOKEN REFRESH FAILED ===")
            (println "Error:" (:error body))
            (println "Description:" (:error_description body))
            (println "\nYour refresh token has expired or is invalid.")
            (println "Please re-authenticate by visiting the app in your browser to get a new token.")
            (println "============================\n")
            (throw (ex-info (str "Token refresh failed: " (:error_description body))
                            {:type :token-expired})))
          (throw e))))))

(defn read_tokens [token_file_name]
  (edn/read-string (slurp token_file_name)))

(defn no_stored_tokens [token_file_name]
  (not (.exists (io/file token_file_name))))

(defn is_token_expired [tokens]
  (let [unix_time_now (quot (System/currentTimeMillis) 1000)
        token_expires_at (:token_expires_at tokens)]
    (> unix_time_now token_expires_at)))

(defn get_tokens [token_file_name]
  (println "get_tokens")
  (when (no_stored_tokens token_file_name)
    (authenticate "1234567")
    (deref token_response_delivered_promise))

  (let [tokens (read_tokens token_file_name)]
    (if (is_token_expired tokens)
      (refresh_tokens tokens)
      tokens)))

;; Account-based token operations (used by account-service)

(defn refresh-account-tokens
  [client-id client-secret tokens store-fn]
  (println "refresh_tokens for account")
  (try
    (let [token_response_json (refresh_token_request client-id client-secret tokens)
          new-tokens (-> token_response_json
                         token_response2token
                         add_token_expires_at)]
      (store-fn new-tokens)
      new-tokens)
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            body (try (json/read-str (:body data) :key-fn keyword) (catch Exception _ nil))]
        (if (and (= 400 (:status data)) body)
          (throw (ex-info (str "Token refresh failed: " (:error_description body))
                          {:type :token-expired}))
          (throw e))))))

(defn get-tokens-for-account
  [client-id client-secret load-fn store-fn]
  (let [tokens (load-fn)]
    (if (and tokens (not (is_token_expired tokens)))
      tokens
      (when tokens
        (refresh-account-tokens client-id client-secret tokens store-fn)))))
