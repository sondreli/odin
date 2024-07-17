(ns odin.db2
  (:require [taoensso.faraday :as far]))

(def client-opts
  {;;; For DynamoDB Local just use some random strings here, otherwise include your
   ;;; production IAM keys:
   :access-key "..."
   :secret-key "..."

   ;;; You may optionally override the default endpoint if you'd like to use DynamoDB
   ;;; Local or a different AWS Region (Ref. http://goo.gl/YmV80o), etc.:
   :endpoint "http://localhost:8000"                   ; For DynamoDB Local
   ;; :endpoint "http://dynamodb.eu-west-1.amazonaws.com" ; For EU West 1 AWS region

   ;;; You may optionally provide your own (pre-configured) instance of the Amazon
   ;;; DynamoDB client for Faraday functions to use.
   ;; :client (AmazonDynamoDBClientBuilder/defaultClient)
   })

(far/list-tables client-opts)
