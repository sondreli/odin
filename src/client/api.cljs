(ns client.api
  "Central API base URL and URI helpers for HTTP requests. Used by both web and mobile.")

(def ^:dynamic *base-url*
  "Base URL for the backend API. Defaults to localhost; override for production or mobile."
  "http://localhost:8080")

(defn uri
  "Build full request URI from path. Path should start with / (e.g. \"/transactions\")."
  [path]
  (str *base-url* path))

(defn uri-with-id
  "Build full URI for a path with an ID segment (e.g. \"/category/\" and id -> \"/category/123\")."
  [path id]
  (str *base-url* path id))

(defn get-token
  "Retrieve the JWT token from localStorage."
  []
  (.getItem js/localStorage "odin-token"))

(defn set-token!
  "Store the JWT token in localStorage."
  [token]
  (.setItem js/localStorage "odin-token" token))

(defn remove-token!
  "Remove the JWT token from localStorage."
  []
  (.removeItem js/localStorage "odin-token"))

(defn auth-header
  "Return authorization header map if a token is stored, or empty map."
  []
  (if-let [token (get-token)]
    {"Authorization" (str "Bearer " token)}
    {}))
