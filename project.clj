(defproject davstore "0.1.0-SNAPSHOT"
  :description "A file storage component with three parts:
    - A blob store, storing in a git-like content-addressing scheme
    - A datomic schema to store blob references with metadata
    - A connector to expose files over webdav"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [net.bendlas/data.xml "1.0.0"]
                 [webnf/base "0.1.19-SNAPSHOT"
                  :exclusions [org.clojure/data.xml]]
                 [webnf/handler "0.1.19-SNAPSHOT"]
                 [webnf/datomic "0.1.19-SNAPSHOT"]
                 [webnf/enlive.clj "0.1.19-SNAPSHOT"]
                 [webnf.deps/universe "0.1.19-SNAPSHOT"
                  :exclusions [org.clojure/data.xml]]
                 [ring/ring-jetty-adapter "1.4.0"]]
  :plugins [[lein-ring "0.9.7"]]
  :ring {:handler davstore.app/davstore
         :nrepl {:start? true :port 4012}})
