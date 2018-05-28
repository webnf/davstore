(defproject webnf/davstore "0.2.0-alpha8-SNAPSHOT"
  :plugins [[lein-modules "0.3.11"]]
  :description "A file storage component with three parts:
    - A blob store, storing in a git-like content-addressing scheme
    - A datomic schema to store blob references with metadata
    - A connector to expose files over webdav"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/server" "src/client" "src/common"]
  :dependencies [[org.clojure/clojure "1.10.0-alpha4"]
                 [org.clojure/data.xml "0.2.0-alpha5"]
                 [webnf/base "_" :upgrade false]
                 [webnf/handler "_" :upgrade false]
                 [webnf/datomic "_" :upgrade false]
                 [webnf/enlive  "_" :upgrade false]
                 [webnf/filestore  "_" :upgrade false]]
  :profiles
  {:dev {:plugins [[lein-ring "0.12.4"]]
         :source-paths ["src/devel"]
         :dependencies [[ring/ring-jetty-adapter "1.7.0-RC1"]]
         :ring {:handler webnf.davstore.test/davstore
                :nrepl {:start? true :port 4012}}}})
