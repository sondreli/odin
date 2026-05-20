(ns odin.services.config-service)

(defn- env
  "Read an environment variable, returning default if not set."
  [var-name default]
  (or (System/getenv var-name) default))

;; All config values are wrapped in delay so they are read at runtime,
;; not at GraalVM native-image build time. Deref with @ at call sites.
(def jwt-secret     (delay (env "ODIN_JWT_SECRET"       (str (java.util.UUID/randomUUID)))))
(def cors-origin    (delay (env "ODIN_CORS_ORIGIN"      "http://localhost:4000")))

(def dynamodb-endpoint
  (delay
    (let [v (System/getenv "ODIN_DYNAMODB_ENDPOINT")]
      (when (and (some? v) (not= v ""))
        v))))
