(ns odin.db-schemas2
  (:require [odin.services.config-service :as config])
  (:import [software.amazon.awssdk.services.dynamodb DynamoDbClient]
           [software.amazon.awssdk.services.dynamodb.model
            CreateTableRequest KeySchemaElement AttributeDefinition
            ProvisionedThroughput GlobalSecondaryIndex Projection
            ProjectionType KeyType ScalarAttributeType ResourceInUseException]
           [software.amazon.awssdk.http.urlconnection UrlConnectionHttpClient]
           [software.amazon.awssdk.auth.credentials StaticCredentialsProvider AwsBasicCredentials]
           [software.amazon.awssdk.regions Region]
           [java.net URI]))

(defn- ks [attr-name key-type]
  (-> (KeySchemaElement/builder)
      (.attributeName attr-name)
      (.keyType key-type)
      (.build)))

(defn- ad [attr-name attr-type]
  (-> (AttributeDefinition/builder)
      (.attributeName attr-name)
      (.attributeType attr-type)
      (.build)))

(defn- pt [rcu wcu]
  (-> (ProvisionedThroughput/builder)
      (.readCapacityUnits (long rcu))
      (.writeCapacityUnits (long wcu))
      (.build)))

(def transaction-schema
  (-> (CreateTableRequest/builder)
      (.tableName "Transaction")
      (.keySchema [(ks "UserId" KeyType/HASH) (ks "Timestamp" KeyType/RANGE)])
      (.attributeDefinitions [(ad "UserId" ScalarAttributeType/S) (ad "Timestamp" ScalarAttributeType/S)])
      (.provisionedThroughput (pt 5 5))
      (.build)))

(def category-schema
  (-> (CreateTableRequest/builder)
      (.tableName "Category")
      (.keySchema [(ks "UserId" KeyType/HASH) (ks "Id" KeyType/RANGE)])
      (.attributeDefinitions [(ad "UserId" ScalarAttributeType/S) (ad "Id" ScalarAttributeType/S)])
      (.provisionedThroughput (pt 5 5))
      (.build)))

(def report-schema
  (-> (CreateTableRequest/builder)
      (.tableName "Report")
      (.keySchema [(ks "UserId" KeyType/HASH) (ks "Id" KeyType/RANGE)])
      (.attributeDefinitions [(ad "UserId" ScalarAttributeType/S) (ad "Id" ScalarAttributeType/S)])
      (.provisionedThroughput (pt 5 5))
      (.build)))

(def tag-schema
  (-> (CreateTableRequest/builder)
      (.tableName "Tag")
      (.keySchema [(ks "UserId" KeyType/HASH) (ks "Id" KeyType/RANGE)])
      (.attributeDefinitions [(ad "UserId" ScalarAttributeType/S) (ad "Id" ScalarAttributeType/S)])
      (.provisionedThroughput (pt 5 5))
      (.build)))

(def filter-schema
  (-> (CreateTableRequest/builder)
      (.tableName "Filter")
      (.keySchema [(ks "UserId" KeyType/HASH) (ks "Id" KeyType/RANGE)])
      (.attributeDefinitions [(ad "UserId" ScalarAttributeType/S) (ad "Id" ScalarAttributeType/S)])
      (.provisionedThroughput (pt 5 5))
      (.build)))

(def user-schema
  (-> (CreateTableRequest/builder)
      (.tableName "User")
      (.keySchema [(ks "UserId" KeyType/HASH)])
      (.attributeDefinitions [(ad "UserId" ScalarAttributeType/S) (ad "Email" ScalarAttributeType/S)])
      (.globalSecondaryIndexes
        [(-> (GlobalSecondaryIndex/builder)
             (.indexName "EmailIndex")
             (.keySchema [(ks "Email" KeyType/HASH)])
             (.projection (-> (Projection/builder) (.projectionType ProjectionType/ALL) (.build)))
             (.provisionedThroughput (pt 5 5))
             (.build))])
      (.provisionedThroughput (pt 5 5))
      (.build)))

(def loan-schema
  (-> (CreateTableRequest/builder)
      (.tableName "Loan")
      (.keySchema [(ks "UserId" KeyType/HASH) (ks "Id" KeyType/RANGE)])
      (.attributeDefinitions [(ad "UserId" ScalarAttributeType/S) (ad "Id" ScalarAttributeType/S)])
      (.provisionedThroughput (pt 5 5))
      (.build)))

(def account-schema
  (-> (CreateTableRequest/builder)
      (.tableName "Account")
      (.keySchema [(ks "UserId" KeyType/HASH) (ks "AccountId" KeyType/RANGE)])
      (.attributeDefinitions [(ad "UserId" ScalarAttributeType/S) (ad "AccountId" ScalarAttributeType/S)])
      (.provisionedThroughput (pt 5 5))
      (.build)))

(defn create-table [client]
  (doseq [[name schema] [["Transaction" transaction-schema]
                          ["Category"    category-schema]
                          ["Report"      report-schema]
                          ["Tag"         tag-schema]
                          ["Filter"      filter-schema]
                          ["User"        user-schema]
                          ["Account"     account-schema]
                          ["Loan"        loan-schema]]]
    (try
      (let [response (.createTable client schema)]
        (println name "table created:" response))
      (catch Exception e
        (if (instance? software.amazon.awssdk.services.dynamodb.model.ResourceInUseException e)
          (println name "table already exists, skipping.")
          (throw e))))))

(defn -main [& args]
  (let [dynamodb-client (let [http-client (-> (UrlConnectionHttpClient/builder) (.build))
                              builder (-> (DynamoDbClient/builder)
                                          (.httpClient http-client)
                                          (.region (Region/of (or (System/getenv "AWS_REGION") "eu-north-1"))))]
                          (if @config/dynamodb-endpoint
                            (-> builder
                                (.endpointOverride (URI. @config/dynamodb-endpoint))
                                (.credentialsProvider
                                  (StaticCredentialsProvider/create
                                    (AwsBasicCredentials/create "local" "local")))
                                (.build))
                            (.build builder)))]
    (create-table dynamodb-client)))
