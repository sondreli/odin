(ns odin.services.user-service
  (:require [odin.db2 :as db2]
            [odin.services.config-service :as config]
            [clojure.data.json :as json]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt])
  (:import [java.time Instant]))

(defn generate-jwt [user-id email]
  (jwt/sign {:user-id user-id
             :email email
             :exp (.getEpochSecond (.plusSeconds (Instant/now) 86400))}
            config/jwt-secret))

(defn verify-jwt [token]
  (try
    (jwt/unsign token config/jwt-secret)
    (catch Exception _
      nil)))

(defn register-user [email password]
  (when (db2/get-user-by-email email)
    (throw (ex-info "Email already registered" {:type :duplicate-email})))
  (let [user-id (str (java.util.UUID/randomUUID))
        password-hash (hashers/derive password)
        now (str (Instant/now))
        user {:user-id user-id
              :email email
              :password-hash password-hash
              :created-at now}]
    (db2/put-user user)
    {:user-id user-id :email email :token (generate-jwt user-id email)}))

(defn login-user [email password]
  (if-let [user (db2/get-user-by-email email)]
    (if (hashers/check password (:password-hash user))
      {:user-id (:user-id user)
       :email (:email user)
       :token (generate-jwt (:user-id user) (:email user))}
      (throw (ex-info "Invalid password" {:type :invalid-credentials})))
    (throw (ex-info "User not found" {:type :invalid-credentials}))))

(defn register-handler [request]
  (let [{:keys [email password]} (:body request)]
    (if (or (empty? email) (empty? password))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "Email and password are required"})}
      (try
        (let [result (register-user email password)]
          {:status 201
           :headers {"Content-Type" "application/json"}
           :body (json/write-str result)})
        (catch clojure.lang.ExceptionInfo e
          (if (= :duplicate-email (:type (ex-data e)))
            {:status 409
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error "Email already registered"})}
            {:status 500
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error "Registration failed"})}))))))

(defn login-handler [request]
  (let [{:keys [email password]} (:body request)]
    (if (or (empty? email) (empty? password))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "Email and password are required"})}
      (try
        (let [result (login-user email password)]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/write-str result)})
        (catch clojure.lang.ExceptionInfo e
          (if (= :invalid-credentials (:type (ex-data e)))
            {:status 401
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error "Invalid email or password"})}
            {:status 500
             :headers {"Content-Type" "application/json"}
             :body (json/write-str {:error "Login failed"})}))))))

(defn me-handler [request]
  (let [user-id (:user-id request)]
    (if-let [user (db2/get-user-by-id user-id)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:user-id (:user-id user) :email (:email user)})}
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error "User not found"})})))

(defn delete-user-handler [request]
  (let [user-id (:user-id request)]
    (try
      (let [deleted-counts (db2/delete-all-user-data user-id)]
        (println "Deleted user" user-id "- counts:" deleted-counts)
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:message "User and all data deleted"
                                :deleted deleted-counts})})
      (catch Exception e
        (println "Failed to delete user" user-id (.getMessage e))
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error "Failed to delete user"})}))))
