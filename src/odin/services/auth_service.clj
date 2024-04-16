(ns odin.services.auth-service
  (:import java.util.Base64)
  (:require [clojure.data.json :as json]
            [clojure.tools.trace :as trace]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [clojure.java.browse :as browse :refer [browse-url]]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [odin.services.auth-service :as auth]
            [ring.adapter.jetty :as jetty]
            [ring.util.codec :as codec]
            [ring.middleware.params :as rmp]
            [ring.middleware.multipart-params :as rmmp]
            [clojure.test :as test :refer [deftest is do-report]]))

(def client_id "516d21d1-39f1-4712-978c-9634f27dd243")
(def client_secret "c6306ed8-08c9-4de3-8ad7-2d345387f2be")
(def token_response_atom (atom {}))
(def token_response_delivered_promise (promise))
(add-watch token_response_atom :watch-changed
           (fn [_ _ old new]
             (when-not (= old new) (deliver token_response_delivered_promise :changed))))

;; Authenticate and authorize
(defn authenticate [state client_id]
  (println "open in browser, authenticate and copy code from the return uri")
  (let [redirect-url "https://localhost"
        url (format "https://api-auth.sparebank1.no/oauth/authorize?client_id=%s&state=%s&redirect_uri=%s&finInst=fid-ostlandet&response_type=code" client_id state redirect-url)]
    (println url)
    (browse-url url)))

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

(defn make_token_request [client_id
                          client_secret
                          code
                          state
                          redirect_uri]
  (client/post "https://api-auth.sparebank1.no/oauth/token"
  ;(client/post "http://localhost:8080"
               {:content-type "application/x-www-form-urlencoded"
                :form-params {:client_id client_id
                              :client_secret client_secret
                              :code code
                              :grant_type "authorization_code"
                              :state state
                              :redirect_uri redirect_uri}}))

(defn refresh_token_request [client_id
                             client_secret
                             {refresh_token :refresh_token}]
  (client/post "https://api-auth.sparebank1.no/oauth/token"
               {:content-type "application/x-www-form-urlencoded"
                :form-params {:client_id client_id
                              :client_secret client_secret
                              :refresh_token refresh_token
                              :grant_type "refresh_token"}}))

(defn token_response2token [response]
  (let [body_string (:body response)
        tokens (json/read-str body_string :key-fn keyword)]
    tokens))

(defn add_token_expires_at [tokens]
  (let [expires_in (:expires_in tokens)
        unix_time_now (quot (System/currentTimeMillis) 1000)
        token_expires_at (+ unix_time_now expires_in)]
    (assoc tokens :token_expires_at token_expires_at)))

(defn store_tokens [tokens]
  (spit "session_tokens.txt" (with-out-str (pr tokens)))
  tokens)

(defn make_tokens [client_id client_secret code state redirect_uri]
  (let [token_response_json (make_token_request client_id client_secret code state redirect_uri)
        tokens (-> token_response_json
                   token_response2token
                   add_token_expires_at)]
    (store_tokens tokens)))

(defn refresh_tokens [tokens]
  (println "refresh_tokens")
  (let [token_response_json (refresh_token_request client_id client_secret tokens)
        tokens (-> token_response_json
                   token_response2token
                   add_token_expires_at)]
    (println "refreshed tokens:")
    (pp/pprint tokens)
    (store_tokens tokens)))

(defn read_tokens [token_file_name]
  (read-string (slurp token_file_name)))

(defn no_stored_tokens [token_file_name]
  (not (.exists (io/file token_file_name))))

(defn is_token_expired [tokens]
  (println "is_token_expired")
  (let [unix_time_now (quot (System/currentTimeMillis) 1000)
        token_expires_at (:token_expires_at tokens)]
    (println (str "unix time now: " unix_time_now))
    (println (str "token expires at: " token_expires_at))
    (println (str "eval: " (> unix_time_now token_expires_at)))
    (> unix_time_now token_expires_at)))


(defn get_tokens [token_file_name]
  (println "get_tokens")
  (when (no_stored_tokens token_file_name)
    (authenticate "1234567" client_id)
    (deref token_response_delivered_promise)) ; wait for callback

  (let [tokens (read_tokens token_file_name)]
    (if (is_token_expired tokens)
      (refresh_tokens tokens)
      tokens)))
