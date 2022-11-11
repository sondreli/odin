(ns odin.core
  (:import java.util.Base64)
  (:require [clojure.data.json :as json]
;            [avro2sql.io :as io]
            [clojure.tools.trace :as trace]
            [clojure.string :as s]
            [clojure.pprint :as pp]
            [clojure.java.browse :as browse :refer [browse-url]]
            [ring.adapter.jetty :as jetty]
            [ring.util.codec :as codec]
            [clojure.test :as test :refer [deftest is do-report]]))

(def client_id "06b5fe9c-5d99-4950-ae94-11b112154a44")

(def client_secret "173cdbcd-2349-4179-897e-d4e79d6f2bf7")

;; Authenticate and authorize
(defn authenticate [state client_id]
               ;state=$1
  (println "open in browser, authenticate and copy code from the return uri")
  (let [;redirect-url (ring.util.codec/url-encode "https://localhost")
        redirect-url "https://localhost"
        url (format "https://api-auth.sparebank1.no/oauth/authorize?client_id=%s&state=%s&redirect_uri=%s&finInst=fid-ostlandet&response_type=code" client_id state redirect-url)]
    (println url)
    (browse-url url)))

(defn run_authenticate []
  (authenticate "123456" client_id)
  (println "post authenticate"))

(defn handler [request]
  (pp/pprint request)
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "Hello world"})

(defn run_server []
  (jetty/run-jetty handler
     {:port 8080
      :join? true}))

;; Redirect 80 to 8080
;; iptables -t nat -A PREROUTING -p tcp --dport 80 -j REDIRECT --to-port 8080
;; iptables -t nat -I OUTPUT -p tcp -d 127.0.0.1 --dport 80 -j REDIRECT --to-ports 8080)
;; need to set up nginx as a reverse proxy in order to recieve traffic on https
;; then it can redirect to 8080 directly. omg


(defn run [opts]
  (println (authenticate "123456" client_id)))

(defn -main [& args]
  ;;(.start (Thread. run_authenticate))
  ;; (jetty/run-jetty handler
  ;;                     {:port 80
  ;;                      :join? true})
  (.start (Thread. run_server))
  (authenticate "123456" client_id)
  (println "Post server?"))
