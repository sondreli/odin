(ns client.routes
  (:require [bidi.bidi :as bidi]
            [pushy.core :as pushy]
            ;; [cemerick.url :refer (url url-encode)]
            [cemerick.url :as url]
            [re-frame.core :as re-frame]
            [client.services.date-service :as date]))

(defn custom-match [key]
  [#"[A-Za-z0-9:%\*\-\.&(),'+_ æøåÆØÅ]+" key])

(def routes
  (atom
   ["/" {"" :home
         ["display-option/" :display-option] :display
         ["category/" :name] {"" :category
                              ["/period/" :start "/" :end] :category-period}
         ["period/" :period-type "/" :start "/" :end]
         {"" :period
          ["/display-option/" :display-option]
          {"" :period-display
           ["/category/" (custom-match :category)] :period-display-category
           ["/category/" (custom-match :category) "/" (custom-match :category-filter)] :period-display-category-filter}}}]))

; /period/:from/:to
; ?period=2024-03-03:2024-04-03

;; (parse "/about")

(defn parse [url]
  (println "parse: " url)
  (println (-> url (url/url) :query))
  (bidi/match-route @routes url))

(defn url-for [& args]
  (apply bidi/path-for (into [@routes] args)))

(defn init-route []
    (let [month-index (date/current-month)
          year (date/current-year)]
      {:handler :period
       :route-params {:start (date/localdate->unixtime (date/first-day-of-month month-index year))
                      :end (date/localdate->unixtime (date/first-day-of-next-month month-index year))
                      :period-type :month
                      :display-option :table}}))

(defn route->period [route]
  (let [route-params (-> route :route-params)]
    (if (and (contains? route-params :start)
             (contains? route-params :end)
             (contains? route-params :period-type))
      {:start (-> route-params :start date/unixtime->localdate)
       :end (-> route-params :end date/unixtime->localdate)
       :period-type (-> route-params :period-type keyword)}
      nil)))

(defn route->filter-path [route]
  (let [category-name (-> route :route-params :category url/url-decode)
        category-filter (-> route :route-params :category-filter url/url-decode)]
    (cond
      (and (some? category-name)
           (some? category-filter)) [category-name category-filter]
      (some? category-name) [category-name]
      :else [])))

(defn dispatch [route-input]
  (let [panel (keyword (str (name (:handler route-input)) "-panel"))
        route (if (-> route-input :handler (= :home)) (init-route) route-input)
        ;; _ (println "routes/dispatch route: " route)
        filter-path (route->filter-path route)
        period (route->period route)
        display-option (-> route :route-params :display-option keyword)]
    ;; (println "routes/dispatch: " period)
    ;; (println "routes/dispatch: " filter-path)
    (re-frame/dispatch [:view-category-period [nil filter-path period display-option]])
    ))

(defn- browser? []
  (and (exists? js/window)
       (exists? js/document)
       (some? (.-createElement js/document))))

(defonce history
  (when (browser?)
    (pushy/pushy dispatch parse)))

(defn navigate! [handler]
  (when history
    (println "navigate! " handler)
    (println (apply url-for handler))
    (pushy/set-token! history (apply url-for handler))))

(defn navigate-to-parameters [period display-option filter-path]
  (when history
    (let [handler-type (case (count filter-path)
                          0 :period-display
                          1 :period-display-category
                          2 :period-display-category-filter)
          filter-path-navigation (case (count filter-path)
                                   0 []
                                   1 [:category (url/url-encode (first filter-path))]
                                   2 [:category (url/url-encode (first filter-path)) :category-filter (url/url-encode (second filter-path))])
          handler (-> [handler-type]
                      (concat (when (some? period) [:start (-> period :start date/localdate->unixtime)
                                                    :end (-> period :end date/localdate->unixtime)
                                                    :period-type (-> period :period-type name)]))
                      (concat (when (some? display-option) [:display-option display-option]))
                      (concat filter-path-navigation))]
      (navigate! handler))))

(defn ensure-period [db period?]
  (if (some? period?) period? (:period db)))

(defn ensure-display-option [db display-option?] 
  ;; (println "ensure-display-option: " (-> db :displayed-transactions-data :display-option))
  (if (some? display-option?) display-option? (-> db :displayed-transactions-data :display-option)))

(defn ensure-filter-path [db filter-path?]
  (if (some? filter-path?) filter-path? (:filter-path db)))

(defn start! [period display-option]
  (when history
    (println "hello bidi")
    (pushy/start! history)
    (let [current-route (bidi/match-route @routes (.-pathname (.-location js/window)))]
      (when (or (nil? current-route) (= :home (:handler current-route)))
        (navigate-to-parameters period display-option nil)))))

(re-frame/reg-event-fx
 :navigate
 (fn [{db :db
       [_ [period display-option filter-path]] :event} _]
   (navigate-to-parameters (ensure-period db period)
                           (ensure-display-option db display-option)
                           (ensure-filter-path db filter-path))))