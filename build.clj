(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'odin/odin)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/odin.jar")
(def basis (delay (b/create-basis {:project "deps.edn"
                                   :aliases [:native]})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber
  "Build an uberjar.
   Default (local dev): clj -T:build uber                    → odin.Main entry point, compiles odin.core (with Jetty)
   Lambda native:       clj -T:build uber :main-ns odin.lambda → odin.lambda entry point, no Jetty"
  [{:keys [main-ns] :or {main-ns 'odin.Main}}]
  (clean nil)
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (b/javac {:src-dirs ["src/java"]
            :class-dir class-dir
            :basis @basis})
  (let [compile-ns (if (= main-ns 'odin.lambda)
                     ['odin.lambda]
                     ['odin.core])]
    (b/compile-clj {:basis @basis
                    :ns-compile compile-ns
                    :class-dir class-dir
                    :java-opts ["-Dclojure.compiler.direct-linking=true"]}))
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :main main-ns})
  (println "Built" uber-file "with main" main-ns))
