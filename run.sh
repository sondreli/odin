clj -X odin.core/-main
#npm run watch

# create local db container
docker run -d -v /Users/sondre/dev/odin/dynamodb_local_db:/dynamodb_local_db -p 8000:8000 --name dynamodb-local amazon/dynamodb-local -jar DynamoDBLocal.jar -sharedDb -dbPath /dynamodb_local_db