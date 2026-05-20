(ns odin.core
  (:require [odin.handler :as handler]
            [ring.adapter.jetty :as jetty]))

(defn run_server []
  (jetty/run-jetty @handler/app-handler
                   {:port 8080
                    :join? true}))

(defn -main [& args]
  (.start (Thread. ^Runnable run_server))
  (println "Odin server started on port 8080"))
