(ns odin.db-schemas2
  (:require [cognitect.aws.client.api :as aws]
            [odin.services.config-service :as config]))

;; commands
; docker run -p 8000:8000 amazon/dynamodb-local
  ;; aws dynamodb get-item \
    ;; --table-name UserTable \
    ;; --key '{"UserId": {"S": "user456"}, "Timestamp": {"N": "30"}}' \
    ;; --endpoint-url http://localhost:8000
    ;; aws dynamodb scan \
    ;;     --table-name UserTable \
    ;;     --endpoint-url http://localhost:8000
    ;; aws dynamodb put-item \
    ;;     --table-name UserTable \
    ;;     --item '{"UserId": {"S": "user456"}, "Name": {"S": "John Doe"}, "Timestamp": {"N": "30"}}' \
    ;;     --endpoint-url http://localhost:8000




; partitionKey: userId (UUID)
; sortKey: timestamp (long)
; combination must be uniq
; when similar timestamp, increment next with one
(def transaction-schema
  {:TableName "Transaction"
   :KeySchema [{:AttributeName "UserId", :KeyType "HASH"}  ; Partition key
               {:AttributeName "Timestamp", :KeyType "RANGE"}  ; Sort key
               ]
   :AttributeDefinitions [{:AttributeName "UserId", :AttributeType "S"}  ; String type for UserId
                          {:AttributeName "Timestamp", :AttributeType "S"}  ; Number type for Timestamp
                          ]
   :ProvisionedThroughput {:ReadCapacityUnits 5
                           :WriteCapacityUnits 5}
  ;;  :GlobalSecondaryIndexes [{:IndexName "TimestampIndex"
  ;;                            :KeySchema [{:AttributeName "Timestamp", :KeyType "HASH"}
  ;;                                        {:AttributeName "UserId", :KeyType "RANGE"}]
  ;;                            :Projection {:ProjectionType "ALL"}
  ;;                            :ProvisionedThroughput {:ReadCapacityUnits 3
  ;;                                                    :WriteCapacityUnits 3}}]
   })
   
(def category-schema
  {:TableName "Category"
   :KeySchema [{:AttributeName "UserId", :KeyType "HASH"}  ; Partition key
               {:AttributeName "Id", :KeyType "RANGE"}  ; Sort key
               ]
   :AttributeDefinitions [{:AttributeName "UserId", :AttributeType "S"}
                          {:AttributeName "Id", :AttributeType "S"}   ; uuid
                          ]
   :ProvisionedThroughput {:ReadCapacityUnits 5
                           :WriteCapacityUnits 5}})

(def report-schema
  {:TableName "Report"
   :KeySchema [{:AttributeName "UserId", :KeyType "HASH"}
               {:AttributeName "Id", :KeyType "RANGE"}]
   :AttributeDefinitions [{:AttributeName "UserId", :AttributeType "S"}
                          {:AttributeName "Id", :AttributeType "S"}]
   :ProvisionedThroughput {:ReadCapacityUnits 5
                           :WriteCapacityUnits 5}})

(def tag-schema
  {:TableName "Tag"
   :KeySchema [{:AttributeName "UserId", :KeyType "HASH"}
               {:AttributeName "Id", :KeyType "RANGE"}]
   :AttributeDefinitions [{:AttributeName "UserId", :AttributeType "S"}
                          {:AttributeName "Id", :AttributeType "S"}]
   :ProvisionedThroughput {:ReadCapacityUnits 5
                           :WriteCapacityUnits 5}})

(def filter-schema
  {:TableName "Filter"
   :KeySchema [{:AttributeName "UserId", :KeyType "HASH"}
               {:AttributeName "Id", :KeyType "RANGE"}]
   :AttributeDefinitions [{:AttributeName "UserId", :AttributeType "S"}
                          {:AttributeName "Id", :AttributeType "S"}]
   :ProvisionedThroughput {:ReadCapacityUnits 5
                           :WriteCapacityUnits 5}})

(def user-schema
  {:TableName "User"
   :KeySchema [{:AttributeName "UserId", :KeyType "HASH"}]
   :AttributeDefinitions [{:AttributeName "UserId", :AttributeType "S"}
                          {:AttributeName "Email", :AttributeType "S"}]
   :GlobalSecondaryIndexes [{:IndexName "EmailIndex"
                             :KeySchema [{:AttributeName "Email", :KeyType "HASH"}]
                             :Projection {:ProjectionType "ALL"}
                             :ProvisionedThroughput {:ReadCapacityUnits 5
                                                     :WriteCapacityUnits 5}}]
   :ProvisionedThroughput {:ReadCapacityUnits 5
                           :WriteCapacityUnits 5}})

(def account-schema
  {:TableName "Account"
   :KeySchema [{:AttributeName "UserId", :KeyType "HASH"}
               {:AttributeName "AccountId", :KeyType "RANGE"}]
   :AttributeDefinitions [{:AttributeName "UserId", :AttributeType "S"}
                          {:AttributeName "AccountId", :AttributeType "S"}]
   :ProvisionedThroughput {:ReadCapacityUnits 5
                           :WriteCapacityUnits 5}})

;; Function to create the table with the defined schema
(defn create-table [client]
  (let [transaction-response (aws/invoke client {:op :CreateTable :request transaction-schema})
        category-response (aws/invoke client {:op :CreateTable :request category-schema})
        report-response (aws/invoke client {:op :CreateTable :request report-schema})
        tag-response (aws/invoke client {:op :CreateTable :request tag-schema})
        filter-response (aws/invoke client {:op :CreateTable :request filter-schema})
        user-response (aws/invoke client {:op :CreateTable :request user-schema})
        account-response (aws/invoke client {:op :CreateTable :request account-schema})]
    (println "Transaction table creation response:" transaction-response)
    (println "Category table creation response:" category-response)
    (println "Report table creation response:" report-response)
    (println "Tag table creation response:" tag-response)
    (println "Filter table creation response:" filter-response)
    (println "User table creation response:" user-response)
    (println "Account table creation response:" account-response)))

(defn -main [& args]
  (let [dynamodb-client (if config/dynamodb-endpoint
                          (let [uri (java.net.URI. config/dynamodb-endpoint)]
                            (aws/client {:api :dynamodb
                                         :endpoint-override {:protocol (keyword (.getScheme uri))
                                                             :hostname (.getHost uri)
                                                             :port (.getPort uri)}}))
                          (aws/client {:api :dynamodb}))]
    (create-table dynamodb-client)))
