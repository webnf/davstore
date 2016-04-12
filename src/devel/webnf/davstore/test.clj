(ns webnf.davstore.test
  (:require
   (webnf.davstore
    [ext :as ext]
    [app :refer
     [blob-handler file-handler wrap-access-control wrap-store]])
   [clojure.pprint :refer [pprint]]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [clojure.data.xml :as xml]
   [net.cgrand.moustache :refer [app]]))

(xml/alias-ns
 :de  :webnf.davstore.entry
 :det :webnf.davstore.entry.type
 :des :webnf.davstore.entry.snapshot
 :dr  :webnf.davstore.root
 :dd  :webnf.davstore.dir
 :dfc :webnf.davstore.file.content
 :dfn :webnf.davstore.fn)

;; Quick and embedded dav server
(def blob-dir "/tmp/davstore-app")
(def db-uri "datomic:mem://davstore-app")
(def root-id #uuid "7178245f-5e6c-30fb-8175-c265b9fe6cb8")

(defn wrap-log [h]
  (fn [req]
    (let [bos (java.io.ByteArrayOutputStream.)
          _ (when-let [body (:body req)] (io/copy body bos))
          bs (.toByteArray bos)
          _ (log/debug (:request-method req) (:uri req)
                       "\nHEADERS:" (with-out-str (pprint (:headers req)))
                       "BODY:" (str \' (String. bs "UTF-8") \'))
          resp (h (assoc req :body (java.io.ByteArrayInputStream. bs)))]
      (log/debug " => RESPONSE" (with-out-str (pprint resp)))
      resp)))

(defn wrap-log-light [h]
  (fn [req]
    (try
      (let [resp (h req)]
        (log/debug (.toUpperCase (name (:request-method req)))
                   (:uri req) " => " (:status resp))
        resp)
      (catch Exception e
        (log/error e (.toUpperCase (name (:request-method req)))
                   (:uri req) " => " 500)))))

(def davstore
  (app
   (wrap-store :blob-path blob-dir
               :db-uri db-uri
               :root-uuid root-id
               :root-uri "/files"
               :ext-props {::ext/index-file
                           (reify ext/ExtensionProperty
                             (db-add-tx [_ e content]
                               [[:db/add (:db/id e) ::dd/index-file (apply str content)]])
                             (db-retract-tx [_ e]
                               (when (contains? e ::dd/index-file)
                                 [[:db/retract (:db/id e) ::dd/index-file (::dd/index-file e)]]))
                             (xml-content [_ e]
                               (::dd/index-file e)))})
   wrap-log-light
   (wrap-access-control "http://localhost:8080")
   ["blob" &] blob-handler
   ["files" &] file-handler
   ["debug"] (fn [req] {:status 400 :debug req})))

(defn get-handler-store
  ([] (get-handler-store davstore))
  ([h] (::store (:debug (h {:request-method :debug :uri "/debug"})))))
