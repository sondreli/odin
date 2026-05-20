(ns odin.lambda
  (:gen-class)
  (:require [odin.handler :as handler]
            [clojure.data.json :as json]
            [clojure.string :as s]
            [clojure.java.io :as io])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]))

(defn- apigw-event->ring-request
  "Convert an API Gateway v2 proxy event to a Ring request map."
  [event]
  (let [http-method (-> (get event "requestContext")
                        (get "http")
                        (get "method")
                        s/lower-case
                        keyword)
        path (get event "rawPath" "/")
        headers (get event "headers" {})
        qs (get event "rawQueryString")
        body (get event "body")
        is-base64 (get event "isBase64Encoded" false)]
    {:server-port 443
     :server-name "lambda"
     :remote-addr (-> (get event "requestContext")
                      (get "http")
                      (get "sourceIp" "0.0.0.0"))
     :uri path
     :scheme :https
     :request-method http-method
     :headers (into {} (map (fn [[k v]] [(s/lower-case k) v]) headers))
     :query-string (when (seq qs) qs)
     :body (when body
             (let [^String decoded (if is-base64
                                     (String. (.decode (java.util.Base64/getDecoder) ^String body))
                                     body)]
               (io/input-stream (.getBytes decoded "UTF-8"))))}))

(defn- ring-response->apigw-response
  "Convert a Ring response map to an API Gateway v2 proxy response."
  [response]
  {"statusCode" (:status response)
   "headers" (or (:headers response) {})
   "body" (or (:body response) "")
   "isBase64Encoded" false})

(defn- http-get
  "Perform a blocking GET request, return the response body as a string."
  [^HttpClient client ^String url]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.GET)
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/ofString))))

(defn- http-post
  "Perform a blocking POST request with a JSON body."
  [^HttpClient client ^String url ^String body]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/ofString))))

(defn- process-event
  "Process a single Lambda invocation event."
  [handler event]
  (let [ring-request (apigw-event->ring-request event)
        _ (println "REQUEST uri:" (:uri ring-request) "method:" (:request-method ring-request))
        ring-response (handler ring-request)
        _ (println "RESPONSE status:" (:status ring-response))]
    (ring-response->apigw-response ring-response)))

(defn- runtime-loop
  "Main Lambda Runtime API polling loop."
  [handler]
  (let [runtime-api (System/getenv "AWS_LAMBDA_RUNTIME_API")
        base-url (str "http://" runtime-api "/2018-06-01/runtime")
        client (HttpClient/newHttpClient)]
    (println "Lambda runtime loop started, polling" runtime-api)
    (loop []
      (try
        ;; 1. Poll for next invocation (blocks until event available)
        (let [^HttpResponse response (http-get client (str base-url "/invocation/next"))
              request-id (-> (.headers response)
                             (.firstValue "lambda-runtime-aws-request-id")
                             (.orElse nil))
              event (json/read-str (.body response))]
          (if request-id
            (try
              ;; 2. Process the event
              (let [result (process-event handler event)
                    result-json (json/write-str result)]
                ;; 3. Post response
                (http-post client
                           (str base-url "/invocation/" request-id "/response")
                           result-json))
              (catch Exception e
                ;; Post error
                (let [error-body (json/write-str
                                   {"errorMessage" (.getMessage e)
                                    "errorType" (.getName (class e))})]
                  (println "Invocation error:" (.getMessage e))
                  (http-post client
                             (str base-url "/invocation/" request-id "/error")
                             error-body))))
            (println "WARNING: No request ID in invocation response")))
        (catch Exception e
          (println "Runtime loop error:" (.getMessage e))
          ;; Brief pause before retrying on unexpected errors
          (Thread/sleep 100)))
      (recur))))

(defn -main [& args]
  (println "Initializing Lambda handler...")
  (let [handler @handler/app-handler]
    (println "Handler initialized, entering runtime loop")
    (runtime-loop handler)))
