(ns odin.auth-service-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [odin.core :as core]
            [clojure.test :as test]))

(def response {:ssl-client-cert nil
               :protocol "HTTP/1.0"
               :remote-addr "127.0.0.1"
               :headers
               {"sec-fetch-site" "cross-site"
                "host" "localhost"
                "user-agent"
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36",
                "referer" "https://api.sparebank1.no/"
                "connection" "close"
                "upgrade-insecure-requests" "1"
                "accept"
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                "accept-language" "en-US,en"
                "sec-fetch-dest" "document"
                "x-forwarded-for" "::1"
                "accept-encoding" "gzip, deflate, br"
                "x-forwarded-proto" "https"
                "sec-fetch-mode" "navigate"
                "x-real-ip" "::1"
                "sec-gpc" "1"}
               :server-port 80
               :content-length nil
               :content-type nil
               :character-encoding nil
               :uri "/"
               :server-name "localhost"
               :query-string "code=924378b5e8fa4d9e96937aa7a3c20855&state=123456"
               :body "#object[org.eclipse.jetty.server.HttpInputOverHTTP 0x2bb3c742 \"HttpInputOverHTTP@2bb3c742[c=0,q=0,[0]=null,s=STREAM]\"]"
               :scheme :http
               :request-method :get})

;; (defn handler [request]
;;   (pp/pprint request)
;;   (let [{code :code state :state} (extract_authenticate_data request) ; extract data from request
;;         token_response_json {:body "{\"access_token\": \"my_token_value\", \"refresh_token\": \"my_refresh_value\"}"}
;;         _ (pp/pprint token_response_json)]
;;     (println "past store")
;;     {:status 200
;;      :headers {"Content-Type" "text/html"}
;;      :body "Authentication successful"}))

;; (defn run_server []
;;   (jetty/run-jetty handler
;;      {:port 8080
;;       :join? true}))

(defn setup-test []
  (println "pre test"))

(defn teardown-test []
  (println "post test"))

(defn wrap-setup [f]
  ;(.start (Thread. run_server))
  (setup-test)
  (f)
  ;; should run teardown in try
  (teardown-test))

(use-fixtures :once wrap-setup)


(deftest extract_authenticate_data_test
  (testing "that the code and group is retrieved from the response"
    (is (=
         (core/extract_authenticate_data response)
         {:code "924378b5e8fa4d9e96937aa7a3c20855"
          :state "123456"}))))



(defn -main [& args]
  (test/run-tests))
