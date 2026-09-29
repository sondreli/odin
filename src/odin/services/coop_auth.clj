(ns odin.services.coop-auth
  "Coop Min Side OIDC (Auth0 at login.coop.no).

   Coop only allows the official web callback
   https://minside.coop.no/api/auth/callback/auth0/
   so we cannot receive the authorization code on our own domain.

   Flow:
   1. Start login → PKCE + redirect user to login.coop.no (BankID).
   2. After login the browser lands on minside.coop.no/.../?code=...
      (Min Side may show an error — that is expected; we only need the URL.)
   3. User pastes that URL back into Odin.
   4. We exchange code + our PKCE verifier for an access token with
      audience https://api.coop.no — the same API the Coop app uses.

   ⚠️ Unofficial use of Coop's public web client. Personal data access only."
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [odin.db2 :as db2]
            [odin.services.account-service :as account-svc]
            [odin.services.auth-service :as auth]
            [odin.services.http-service :as http])
  (:import [java.net URLDecoder URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [java.time Instant]))

(def client-id "RdbMJKxmpiqvlYR1DPAwrE1y5D3wJvXj")
(def redirect-uri "https://minside.coop.no/api/auth/callback/auth0/")
(def authorize-url "https://login.coop.no/authorize")
(def token-url "https://login.coop.no/oauth/token")
(def audience "https://api.coop.no")
(def scope "openid profile email offline_access")

(defn- b64url [^bytes bs]
  (-> (Base64/getUrlEncoder)
      .withoutPadding
      (.encodeToString bs)))

(defn generate-pkce
  "RFC 7636 S256 verifier + challenge."
  []
  (let [raw (byte-array 32)
        _ (.nextBytes (SecureRandom.) raw)
        verifier (b64url raw)
        challenge (b64url (.digest (MessageDigest/getInstance "SHA-256")
                                   (.getBytes ^String verifier "US-ASCII")))]
    {:verifier verifier :challenge challenge}))

(defn- url-encode [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- url-decode [s]
  (URLDecoder/decode (str s) StandardCharsets/UTF_8))

(defn build-authorize-url
  [account-id code-challenge]
  (str authorize-url
       "?response_type=code"
       "&client_id=" (url-encode client-id)
       "&redirect_uri=" (url-encode redirect-uri)
       "&audience=" (url-encode audience)
       "&scope=" (url-encode scope)
       "&state=" (url-encode account-id)
       "&code_challenge=" (url-encode code-challenge)
       "&code_challenge_method=S256"))

(defn extract-code
  "Accept a raw authorization code or a full minside callback URL containing ?code=."
  [s]
  (let [t (s/trim (str s))]
    (cond
      (s/blank? t) nil
      (re-find #"(?i)^https?://" t)
      (when-let [qs (second (s/split t #"\?" 2))]
        (let [params (->> (s/split qs #"[&#]")
                          (map #(s/split % #"=" 2))
                          (filter #(= 2 (count %)))
                          (map (fn [[k v]] [(url-decode k) (url-decode v)]))
                          (into {}))]
          (not-empty (get params "code"))))
      :else t)))

(defn- parse-token-data [account]
  (when-let [td (:token-data account)]
    (try (json/read-str td :key-fn keyword)
         (catch Exception _ nil))))

(defn- persist-tokens! [user-id account-id tokens]
  (let [with-exp (auth/add_token_expires_at tokens)
        stored (assoc with-exp :client_id client-id :redirect_uri redirect-uri)]
    (account-svc/store-account-tokens user-id account-id stored)
    ;; grocery_service historically reads :client-secret as the bearer token
    (db2/update-account-client-secret user-id account-id (:access_token stored))
    stored))

(defn exchange-code!
  [code verifier]
  (let [resp (try
               (http/http-post-form token-url
                                    {:form-params {:grant_type "authorization_code"
                                                   :client_id client-id
                                                   :code code
                                                   :redirect_uri redirect-uri
                                                   :code_verifier verifier}})
               (catch clojure.lang.ExceptionInfo e
                 (let [data (ex-data e)
                       body (try (json/read-str (:body data) :key-fn keyword)
                                 (catch Exception _ nil))
                       desc (or (:error_description body) (:error body) (.getMessage e))
                       used? (re-find #"(?i)invalid authorization code|invalid_grant" (str desc))]
                   (throw (ex-info
                           (if used?
                             (str "Koden er allerede brukt. Min Side bytter den mot token "
                                  "i det callback lastes, så den er ugyldig når du limer inn. "
                                  "Blokker callback-URLen i DevTools og logg inn på nytt "
                                  "(se stegene i skjemaet).")
                             desc)
                           {:status 400})))))]
    (json/read-str (:body resp) :key-fn keyword)))

(defn refresh-tokens!
  [refresh-token]
  (let [resp (try
               (http/http-post-form token-url
                                    {:form-params {:grant_type "refresh_token"
                                                   :client_id client-id
                                                   :refresh_token refresh-token}})
               (catch clojure.lang.ExceptionInfo e
                 (let [data (ex-data e)
                       body (try (json/read-str (:body data) :key-fn keyword)
                                 (catch Exception _ nil))]
                   (throw (ex-info (or (:error_description body)
                                       (:error body)
                                       (.getMessage e))
                                   {:type :token-expired})))))]
    (json/read-str (:body resp) :key-fn keyword)))

(defn start-login!
  "Create or reuse a Coop account and return the Auth0 authorize URL."
  [user-id {:keys [account-id account-name]}]
  (let [pkce (generate-pkce)
        acc (if account-id
              (or (db2/get-account user-id account-id)
                  (throw (ex-info "Account not found" {:status 404})))
              (account-svc/create-account user-id "coop"
                                          (or account-name "Coop")
                                          client-id nil redirect-uri))
        pending {:pkce_verifier (:verifier pkce)
                 :redirect_uri redirect-uri
                 :client_id client-id
                 :started_at (str (Instant/now))}]
    (when (and account-id (not= "coop" (:provider acc)))
      (throw (ex-info "Account is not a Coop account" {:status 400})))
    (account-svc/store-account-tokens user-id (:account-id acc) pending)
    {:account (dissoc acc :client-secret :token-data)
     :oauth-url (build-authorize-url (:account-id acc) (:challenge pkce))
     :coop-login true}))

(defn complete-login!
  [user-id account-id callback]
  (let [account (db2/get-account user-id account-id)
        pending (parse-token-data account)
        verifier (:pkce_verifier pending)
        code (extract-code callback)]
    (cond
      (nil? account)
      (throw (ex-info "Account not found" {:status 404}))

      (not= "coop" (:provider account))
      (throw (ex-info "Account is not a Coop account" {:status 400}))

      (s/blank? verifier)
      (throw (ex-info "Ingen pågående Coop-innlogging. Trykk «Logg inn med Coop» på nytt."
                      {:status 400}))

      (and (string? callback)
           (re-find #"/u/login/identifier" (str callback)))
      (throw (ex-info (str "Dette er innloggingssiden, ikke callback-URLen. "
                           "Fullfør BankID og hent URLen fra DevTools → Network "
                           "(se etter callback/auth0?code=).")
                      {:status 400}))

      (and (string? callback)
           (re-find #"minside\.coop\.no" (str callback))
           (s/blank? code))
      (throw (ex-info (str "Du er på Min Side-forsiden. Coop fjerner code= fra adresselinjen med en gang. "
                           "Åpne DevTools (F12) → Network → huk av Preserve log → filtrer på callback. "
                           "Finn kallet til minside.coop.no/api/auth/callback/auth0/?code=... og kopier Request URL.")
                      {:status 400}))

      (s/blank? code)
      (throw (ex-info "Fant ingen code i URL-en. Bruk Request URL fra Network-fanen (må inneholde code=)."
                      {:status 400}))

      :else
      (let [tokens (exchange-code! code verifier)]
        (when-not (:access_token tokens)
          (throw (ex-info (or (:error_description tokens)
                              "Coop returnerte ikke noe tilgangstoken")
                          {:status 502 :body tokens})))
        (persist-tokens! user-id account-id tokens)
        {:ok true :expires-in (:expires_in tokens)}))))

(defn access-token
  "Return a valid Coop access token, refreshing if needed."
  [user-id account]
  (let [td (or (parse-token-data account) {})
        access (or (:access_token td) (:client-secret account))
        expired? (and (:token_expires_at td)
                      (auth/is_token_expired td))]
    (cond
      (and (not expired?) (not (s/blank? access)))
      access

      (:refresh_token td)
      (let [fresh (refresh-tokens! (:refresh_token td))
            ;; Auth0 refresh responses may omit refresh_token; keep the old one
            merged (merge td fresh)
            stored (persist-tokens! user-id (:account-id account) merged)]
        (:access_token stored))

      (not (s/blank? access))
      access

      :else
      nil)))

;; Handlers

(defn- json-error [status msg]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:error msg})})

(defn start-handler [request]
  (try
    (let [user-id (:user-id request)
          body (or (:body request) {})
          result (start-login! user-id body)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str result)})
    (catch Exception e
      (json-error (or (:status (ex-data e)) 500) (.getMessage e)))))

(defn complete-handler [account-id request]
  (try
    (let [user-id (:user-id request)
          callback (or (some-> request :body :callback-url)
                       (some-> request :body :code))
          result (complete-login! user-id account-id callback)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str result)})
    (catch Exception e
      (json-error (or (:status (ex-data e)) 500) (.getMessage e)))))
