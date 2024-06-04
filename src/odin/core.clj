(ns odin.core
  (:import java.util.Base64)
  (:require [clojure.data.json :as json]
            [clojure.tools.trace :as trace]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [clojure.java.browse :as browse :refer [browse-url]]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [compojure.core :as cpj]
            [odin.services.auth-service :as auth]
            [odin.services.transaction-service :as transaction]
            [odin.services.category-service :as category]
            [ring.adapter.jetty :as jetty]
            [ring.util.codec :as codec]
            [ring.middleware.params :as rmp]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.multipart-params :as rmmp]
            [ring.middleware.cors :refer [wrap-cors]]
            [clojure.test :as test :refer [deftest is do-report]]))


(defn token_handler [request]
  (pp/pprint request)
  (let [auth_data (auth/extract_authenticate_data request)]
        ; build token request and retrieve token
        ; store access_token and refresh_token in atoms
    (if auth_data
      (let [{code :code state :state} auth_data
            _ (println (str "code : " code))
            _ (println (str "state : " state))
            tokens (auth/make_tokens auth/client_id auth/client_secret code state "https://localhost")]
        (reset! auth/token_response_atom tokens)
        (println "past store")
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body "Authentication successful"})
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Second respone"})))

;(make_token_request "id" "secret" "code" "state" "redirect_url:}))

(cpj/defroutes app
  (cpj/GET "/" params token_handler) ; misses some favicon.ico requests
  (cpj/GET "/transactions/:id/details" [id] (partial transaction/transaction_details_handler id))
  (cpj/GET "/transactions" params transaction/transaction_handler)
  (cpj/GET "/categories" params category/categories-handler)
  (cpj/POST "/category" params category/store-category-handler)
  (cpj/POST "/transactions/update" params category/update-transactions-with-categories)
  )

(def app-handler
  (-> app
      (wrap-json-body {:key-fn keyword})
      (wrap-cors :access-control-allow-origin [#"http://localhost:4000"]
                 :access-control-allow-methods [:get :put :post :delete])
      ;; rmp/wrap-params
      ;; rmmp/wrap-multipart-params
      ))

(defn run_server []
  (jetty/run-jetty app-handler
     {:port 8080
      :join? true}))

(defn -main [& args]
  (.start (Thread. run_server))
  (transaction/get-all-transactions)

  (println "Post server?"))

;(-main)
