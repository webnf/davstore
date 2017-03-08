(defproject webnf/davstore "0.2.0-alpha2"
  :plugins [[lein-modules "0.3.11"]]
  :description "A file storage component with three parts:
    - A blob store, storing in a git-like content-addressing scheme
    - A datomic schema to store blob references with metadata
    - A connector to expose files over webdav"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/server" "src/client" "src/common"]
  :dependencies [[org.clojure/clojure "1.9.0-alpha14"]
                 [org.clojure/data.xml "0.2.0-alpha2"]
                 [webnf/base "_" :upgrade false]
                 [webnf/handler "_" :upgrade false]
                 [webnf/datomic "_" :upgrade false]
                 [webnf/enlive  "_" :upgrade false]
                 [webnf/filestore  "_" :upgrade false]]
  :profiles
  {:dev {:plugins [[lein-ring "0.11.0"]]
         :source-paths ["src/devel"]
         :dependencies [[ring/ring-jetty-adapter "1.6.0-beta7"]]
         :ring {:handler webnf.davstore.test/davstore
                :nrepl {:start? true :port 4012}}}})
