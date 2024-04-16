(ns odin.core-test
  (:require [clojure.test :refer deftest is do-reporting testing]
            [odin.core :as core]))

(def response {:ssl-client-cert nil
               :protocol "HTTP/1.0"
               :remote-addr "127.0.0.1"
               :headers
               {"sec-fetch-site" "cross-site"
                "host" "localhost"
                "user-agent"
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36",
                "referer" "https://api.sparebank1.no/"
                "connection" "close"
                "upgrade-insecure-requests" "1"
                "accept"
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                "accept-language" "en-US,en"
                "sec-fetch-dest" "document"
                "x-forwarded-for" "::1"
                "accept-encoding" "gzip, deflate, br"
                "x-forwarded-proto" "https"
                "sec-fetch-mode" "navigate"
                "x-real-ip" "::1"
                "sec-gpc" "1"}
               :server-port 80
               :content-length nil
               :content-type nil
               :character-encoding nil
               :uri "/"
               :server-name "localhost"
               :query-string "code=924378b5e8fa4d9e96937aa7a3c20855&state=123456"
               :body "#object[org.eclipse.jetty.server.HttpInputOverHTTP 0x2bb3c742 \"HttpInputOverHTTP@2bb3c742[c=0,q=0,[0]=null,s=STREAM]\"]"
               :scheme :http
               :request-method :get})

(core/get-code response)
