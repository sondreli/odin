(ns odin.services.http-service
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:private client
  (delay
    (-> (HttpClient/newBuilder)
        (.followRedirects HttpClient$Redirect/NORMAL)
        (.build))))

(defn- url-encode [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- build-query-string [params]
  (when (seq params)
    (->> params
         (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode v))))
         (clojure.string/join "&"))))

(defn- build-form-body [params]
  (->> params
       (map (fn [[k v]] (str (url-encode (name k)) "=" (url-encode v))))
       (clojure.string/join "&")))

(defn http-get
  "Perform a GET request. opts supports :headers and :query-params.
   Returns {:status int :body string}."
  [url {:keys [headers query-params]}]
  (let [full-url (if (seq query-params)
                   (str url "?" (build-query-string query-params))
                   url)
        builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create full-url))
                    (.GET))]
    (doseq [[k v] headers]
      (.header builder (name k) (str v)))
    (let [response (.send ^HttpClient @client (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :body   (.body response)})))

(defn http-post-form
  "Perform a POST with form-urlencoded body. opts supports :form-params and :headers.
   Returns {:status int :body string}.
   Throws ex-info on non-2xx status (matching clj-http behavior for error handling)."
  [url {:keys [form-params headers]}]
  (let [body (build-form-body form-params)
        builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/x-www-form-urlencoded")
                    (.POST (HttpRequest$BodyPublishers/ofString body)))]
    (doseq [[k v] headers]
      (.header builder (name k) (str v)))
    (let [response (.send ^HttpClient @client (.build builder) (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)
          result {:status status :body (.body response)}]
      (when (>= status 400)
        (throw (ex-info (str "HTTP " status) result)))
      result)))
