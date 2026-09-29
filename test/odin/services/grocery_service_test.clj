(ns odin.services.grocery-service-test
  (:require [clojure.test :refer [deftest is testing]]
            [odin.services.grocery-service :as grocery]
            [odin.services.coop-auth :as coop]
            [odin.services.account-service :as account]))

(deftest grocery-providers-registered
  (testing "Trumf, Rema and Coop are grocery-type providers"
    (is (= :grocery (:type (account/get-provider "trumf"))))
    (is (= :grocery (:type (account/get-provider "rema"))))
    (is (= :grocery (:type (account/get-provider "coop"))))
    (is (= "Trumf" (:name (account/get-provider "trumf"))))
    (is (= "Rema 1000" (:name (account/get-provider "rema"))))
    (is (= "Coop" (:name (account/get-provider "coop"))))))

(deftest non-grocery-providers-unchanged
  (is (= :oauth (:type (account/get-provider "sparebank1-ost"))))
  (is (= :csv (:type (account/get-provider "nordnet")))))

(deftest coop-pkce-and-code-extract
  (testing "PKCE verifier/challenge are generated"
    (let [pkce (coop/generate-pkce)]
      (is (string? (:verifier pkce)))
      (is (string? (:challenge pkce)))
      (is (>= (count (:verifier pkce)) 32))
      (is (not= (:verifier pkce) (:challenge pkce)))))

  (testing "extract-code accepts raw codes and minside callback URLs"
    (is (= "abc.def" (coop/extract-code "abc.def")))
    (is (= "THECODE"
           (coop/extract-code "https://minside.coop.no/api/auth/callback/auth0/?code=THECODE&state=xyz")))
    (is (nil? (coop/extract-code "")))
    (is (nil? (coop/extract-code "https://minside.coop.no/api/auth/callback/auth0/?error=access_denied")))
    (is (nil? (coop/extract-code "https://login.coop.no/u/login/identifier?state=abc")))))

  (testing "authorize URL targets Coop Min Side client and api.coop.no audience"
    (let [url (coop/build-authorize-url "acc-1" "challenge")]
      (is (re-find #"login\.coop\.no/authorize" url))
      (is (re-find #"audience=https%3A%2F%2Fapi.coop.no" url))
      (is (re-find #"client_id=RdbMJKxmpiqvlYR1DPAwrE1y5D3wJvXj" url))
      (is (re-find #"code_challenge=challenge" url)))))
