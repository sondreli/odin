(ns odin.services.config-service)

(defn- env
  "Read an environment variable, returning default if not set."
  [var-name default]
  (or (System/getenv var-name) default))

(def client-id      (env "ODIN_CLIENT_ID"      "516d21d1-39f1-4712-978c-9634f27dd243"))
(def client-secret  (env "ODIN_CLIENT_SECRET"   "c6306ed8-08c9-4de3-8ad7-2d345387f2be"))
(def redirect-base  (env "ODIN_REDIRECT_BASE"   "https://localhost"))
(def jwt-secret     (env "ODIN_JWT_SECRET"       (str (java.util.UUID/randomUUID))))
(def cors-origin    (env "ODIN_CORS_ORIGIN"      "http://localhost:4000"))

(def dynamodb-endpoint
  (let [v (System/getenv "ODIN_DYNAMODB_ENDPOINT")]
    (if (some? v)
      (when (not= v "") v)
      "http://localhost:8000")))

(defn redirect-uri
  "Build a full redirect URI for OAuth callbacks."
  [path]
  (str redirect-base path))
