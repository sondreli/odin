(ns odin.db-schemas2
  (:require [cognitect.aws.client.api :as aws]))

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

;; Function to create the table with the defined schema
(defn create-table [client]
  (let [
        transaction-response (aws/invoke client {:op :CreateTable :request transaction-schema})
        category-response (aws/invoke client {:op :CreateTable :request category-schema})
        ]
    (println "Transaction table creation response:" transaction-response)
    (println "Category table creation response:" category-response)
    ))

;; Example usage
(defn -main [& args]
  (let [dynamodb-client (aws/client {:api :dynamodb
                                     :endpoint-override {:protocol :http
                                                         :hostname "localhost"
                                                         :port 8000}})]  ; For local DynamoDB
    (create-table dynamodb-client)))
