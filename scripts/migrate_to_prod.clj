#!/usr/bin/env clojure
;; Migrate categories, filters, and tags from local DynamoDB to production.
;;
;; Usage:
;;   clj -M scripts/migrate_to_prod.clj
;;
;; Requires AWS credentials configured for the prod region (eu-west-1).
;; The local DB is expected at http://localhost:8000.

(ns scripts.migrate-to-prod
  (:import [software.amazon.awssdk.services.dynamodb DynamoDbClient]
           [software.amazon.awssdk.services.dynamodb.model
            ScanRequest QueryRequest PutItemRequest AttributeValue]
           [software.amazon.awssdk.http.urlconnection UrlConnectionHttpClient]
           [software.amazon.awssdk.auth.credentials StaticCredentialsProvider AwsBasicCredentials]
           [software.amazon.awssdk.regions Region]
           [java.net URI]))

;; ---- Configuration ----

(def local-endpoint "http://localhost:8000")
(def local-email "pinne@lurkenlark.com")
(def prod-email "sondre.lima@gmail.com")
(def prod-region "eu-west-1")
(def tables-to-migrate ["Category" "Filter" "Tag"])

;; ---- Client builders ----

(defn make-client [& {:keys [endpoint]}]
  (let [http (-> (UrlConnectionHttpClient/builder) (.build))
        builder (-> (DynamoDbClient/builder)
                    (.httpClient http)
                    (.region (Region/of prod-region)))]
    (if endpoint
      (-> builder
          (.endpointOverride (URI. endpoint))
          (.credentialsProvider
            (StaticCredentialsProvider/create
              (AwsBasicCredentials/create "local" "local")))
          (.build))
      (.build builder))))

;; ---- SDK helpers ----

(defn attr-s [^AttributeValue av] (.s av))

(defn sdk-item->map [sdk-item]
  (into {} (for [[k v] sdk-item]
             [k (cond
                  (not (empty? (.s v))) {:S (.s v)}
                  (not (empty? (.n v))) {:N (.n v)}
                  (.hasSs v)            {:SS (vec (.ss v))}
                  :else                 {:S (.s v)})])))

(defn map->sdk-item [m]
  (into {} (for [[k v] m]
             [k (cond
                  (:S v) (-> (AttributeValue/builder) (.s (:S v)) (.build))
                  (:N v) (-> (AttributeValue/builder) (.n (:N v)) (.build))
                  (:SS v) (-> (AttributeValue/builder) (.ss (:SS v)) (.build))
                  :else   (-> (AttributeValue/builder) (.s (str v)) (.build)))])))

(defn query-by-email [^DynamoDbClient client email]
  (let [req (-> (QueryRequest/builder)
                (.tableName "User")
                (.indexName "EmailIndex")
                (.keyConditionExpression "#e = :email")
                (.expressionAttributeNames {"#e" "Email"})
                (.expressionAttributeValues
                  {":email" (-> (AttributeValue/builder) (.s email) (.build))})
                (.build))
        resp (.query client req)]
    (first (.items resp))))

(defn scan-table-for-user [^DynamoDbClient client table-name user-id]
  (loop [items [] last-key nil]
    (let [builder (-> (ScanRequest/builder)
                      (.tableName table-name)
                      (.filterExpression "UserId = :uid")
                      (.expressionAttributeValues
                        {":uid" (-> (AttributeValue/builder) (.s user-id) (.build))}))
          builder (if last-key (.exclusiveStartKey builder last-key) builder)
          resp (.scan client (.build builder))
          new-items (map sdk-item->map (.items resp))
          all-items (concat items new-items)]
      (if (.hasLastEvaluatedKey resp)
        (recur all-items (.lastEvaluatedKey resp))
        (vec all-items)))))

(defn put-item! [^DynamoDbClient client table-name item]
  (let [req (-> (PutItemRequest/builder)
                (.tableName table-name)
                (.item (map->sdk-item item))
                (.build))]
    (.putItem client req)))

;; ---- Main ----

(defn -main []
  (println "=== Migrate categories, filters, tags: local -> prod ===\n")

  (println "Connecting to local DynamoDB at" local-endpoint "...")
  (let [local-client (make-client :endpoint local-endpoint)]

    (println "Looking up local user:" local-email)
    (let [local-user (query-by-email local-client local-email)]
      (when-not local-user
        (println "ERROR: Local user not found:" local-email)
        (System/exit 1))
      (let [local-user-id (attr-s (get local-user "UserId"))]
        (println "  Found local user-id:" local-user-id)

        (println "\nConnecting to prod DynamoDB in" prod-region "...")
        (let [prod-client (make-client)]

          (println "Looking up prod user:" prod-email)
          (let [prod-user (query-by-email prod-client prod-email)]
            (when-not prod-user
              (println "ERROR: Prod user not found:" prod-email)
              (System/exit 1))
            (let [prod-user-id (attr-s (get prod-user "UserId"))]
              (println "  Found prod user-id:" prod-user-id)

              (doseq [table tables-to-migrate]
                (println (str "\n--- " table " ---"))
                (let [items (scan-table-for-user local-client table local-user-id)]
                  (println "  Found" (count items) "items in local DB")
                  (doseq [item items]
                    (let [new-item (assoc item "UserId" {:S prod-user-id})]
                      (put-item! prod-client table new-item)
                      (let [id-val (or (get-in item ["Id" :S])
                                       (get-in item ["Name" :S])
                                       "?")]
                        (print "."))))
                  (println)
                  (println "  Wrote" (count items) "items to prod")))

              (println "\n=== Migration complete ==="))))))))

(-main)
