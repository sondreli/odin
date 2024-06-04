(ns odin.db-schemas)

(def transaction-schema [{:db/ident :transaction/description
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one
                          :db/doc "Text describing what have been transacted"}

                         {:db/ident :transaction/amount
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one
                          :db/doc "The amount moved in the transaction"}

                         {:db/ident :transaction/date
                          :db/valueType :db.type/long
                          :db/cardinality :db.cardinality/one
                          :db/doc "When the transaction happened in unixtime"}

                         {:db/ident :transaction/category-id
                          :db/valueType :db.type/ref
                          :db/cardinality :db.cardinality/one
                          :db/doc "The id of the category this transaction is assigned to"}

                         {:db/ident :transaction/source
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one
                          :db/doc "The source data for the transaction retrieved from the bank"}])

(def category-schema [
                    ;;   {:db/ident :category/id
                    ;;    :db/valueType :db.type/uuid
                    ;;    :db/unique :db.unique/identity
                    ;;    :db/cardinality :db.cardinality/one
                    ;;    :db/doc "Unique identifyer for the category that allows all other attributes to be modified"}
                       
                      {:db/ident :category/name
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc "Name of the category"}

                      {:db/ident :category/color
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc "Color code of the category"}
                      
                      {:db/ident :category/color-value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc "The value the user inputs for the color"}

                      {:db/ident :category/marker
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc "The strings the user inputs for identifying the category"}])
