(ns odin.services.migration-service
  (:require [odin.db2 :as db2]
            [odin.services.user-service :as user]
            [odin.services.account-service :as account]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.time Instant]))

(def ^:private table-key-schemas
  {"Transaction" {:pk :UserId :sk :Timestamp}
   "Category"    {:pk :UserId :sk :Id}
   "Report"      {:pk :UserId :sk :Id}
   "Tag"         {:pk :UserId :sk :Id}
   "Filter"      {:pk :UserId :sk :Id}})

(defn- migrate-table-items!
  "Copy all items with old-user-id to new-user-id, then delete originals.
   DynamoDB doesn't allow updating partition keys, so we copy+delete."
  [table-name old-user-id new-user-id]
  (let [{:keys [pk sk]} (get table-key-schemas table-name)
        items (db2/scan-table-for-user table-name old-user-id)
        _ (println (str "  " table-name ": found " (count items) " items to migrate"))]
    (doseq [item items]
      (let [new-item (assoc item pk {:S new-user-id})
            old-key (cond-> {pk (get item pk)}
                      sk (assoc sk (get item sk)))]
        (db2/write-item table-name new-item)
        (db2/delete-item table-name old-key)))
    (println (str "  " table-name ": migrated " (count items) " items"))))

(defn migrate-xxx-to-user!
  "Create a user with the given email and password, then migrate all data
   from UserId 'xxx' to the new user's ID. Also migrates session_tokens.txt
   to an Account record if the file exists.
   
   Usage from REPL:
     (migration/migrate-xxx-to-user! \"pinne@lurkenlark.com\" \"your-password\")"
  [email password]
  (println "=== Starting migration ===")
  (println "Creating user:" email)

  (let [result (user/register-user email password)
        new-user-id (:user-id result)]
    (println "Created user with ID:" new-user-id)

    (println "\nMigrating tables...")
    (doseq [table-name (keys table-key-schemas)]
      (migrate-table-items! table-name "xxx" new-user-id))

    (when (.exists (io/file "session_tokens.txt"))
      (println "\nMigrating session_tokens.txt to Account record...")
      (let [tokens (edn/read-string (slurp "session_tokens.txt"))
            acct (account/create-account new-user-id "sparebank1-ost" "Sparebank1 Østlandet")]
        (account/store-account-tokens new-user-id (:account-id acct) tokens)
        (println "Created account" (:account-id acct) "with tokens")))

    (println "\n=== Migration complete ===")
    (println "User ID:" new-user-id)
    (println "Email:" email)
    {:user-id new-user-id :email email}))

(defn migrate-tables-for-existing-user!
  "Migrate data from UserId 'xxx' to an existing user (looked up by email).
   Optionally specify which tables to migrate; defaults to all non-Transaction tables.

   Usage from REPL:
     (migration/migrate-tables-for-existing-user! \"pinne@lurkenlark.com\")
     (migration/migrate-tables-for-existing-user! \"pinne@lurkenlark.com\" [\"Category\" \"Report\"])"
  ([email] (migrate-tables-for-existing-user! email ["Category" "Report" "Tag" "Filter"]))
  ([email table-names]
   (let [user (db2/get-user-by-email email)]
     (if-not user
       (println "ERROR: No user found with email" email)
       (let [new-user-id (:user-id user)]
         (println "=== Migrating tables for user" email "(" new-user-id ") ===\n")
         (doseq [table-name table-names]
           (migrate-table-items! table-name "xxx" new-user-id))
         (println "\n=== Done ===")
         {:user-id new-user-id :tables-migrated table-names})))))
