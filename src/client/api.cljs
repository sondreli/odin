(ns client.api
  "Central API base URL and URI helpers for HTTP requests. Used by both web and mobile.")

(goog-define API_BASE "http://localhost:8080")

(def ^:dynamic *base-url*
  "Base URL for the backend API. Defaults to localhost; overridden to /api in release builds."
  API_BASE)

(defn uri
  "Build full request URI from path. Path should start with / (e.g. \"/transactions\")."
  [path]
  (str *base-url* path))

(defn uri-with-id
  "Build full URI for a path with an ID segment (e.g. \"/category/\" and id -> \"/category/123\")."
  [path id]
  (str *base-url* path id))

(defn- storage-available? []
  (and (exists? js/window) (some? (.-localStorage js/window))))

(defonce ^:private token-atom (atom nil))

(defn get-token
  "Retrieve the JWT token from localStorage, or in-memory fallback on mobile."
  []
  (if (storage-available?)
    (.getItem js/localStorage "odin-token")
    @token-atom))

(defn set-token!
  "Store the JWT token in localStorage, or in-memory fallback on mobile."
  [token]
  (if (storage-available?)
    (.setItem js/localStorage "odin-token" token)
    (reset! token-atom token)))

(defn remove-token!
  "Remove the JWT token from localStorage, or in-memory fallback on mobile."
  []
  (if (storage-available?)
    (.removeItem js/localStorage "odin-token")
    (reset! token-atom nil)))

(defn token-expired?
  "Check if a JWT token is expired by decoding the payload and reading the exp claim.
   Returns true if expired or unparseable, false if still valid."
  [token]
  (try
    (let [parts (.split token ".")
          payload (aget parts 1)
          json (js/atob payload)
          claims (js/JSON.parse json)
          exp (.-exp claims)
          now (/ (.now js/Date) 1000)]
      (> now exp))
    (catch :default _ true)))

(defn auth-header
  "Return authorization header map if a token is stored, or empty map."
  []
  (if-let [token (get-token)]
    {"Authorization" (str "Bearer " token)}
    {}))
