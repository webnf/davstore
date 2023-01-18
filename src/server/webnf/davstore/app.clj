(ns webnf.davstore.app
  (:require [clojure.java.io :as io]
            [clojure.data.xml :as xml]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :as log]
            [datomic.api :as d :refer [delete-database connect]]
            [net.cgrand.moustache :refer [app uri-segments path-info-segments uri]]
            [ring.middleware.head :refer [wrap-head]]
            [ring.util.response :refer [response header status content-type not-found]]
            [webnf.kv :refer [map-keys]]
            [webnf.filestore :as blob]
            [webnf.datomic :as wd]
            (webnf.davstore
             [dav :as dav]
             [store :as store]
             [ext :as ext])
            [webnf.davstore.entry :as-alias de]
            [webnf.davstore.entry.type :as-alias det]
            [webnf.davstore.entry.snapshot :as-alias des]
            [webnf.davstore.root :as-alias dr]
            [webnf.davstore.dir :as-alias dd]
            [webnf.davstore.file.content :as-alias dfc]
            [webnf.davstore.fn :as-alias dfn]))

;; Middlewares

(defn wrap-store [h & {:keys [blob-path db-uri root-uuid root-dir ext-props add-schema]}]
  (assert (and blob-path db-uri root-uuid root-dir))
  (let [store (blob/make-store! blob-path)
        {:keys [conn] :as dstore}
        (assoc (store/init-store! db-uri store root-uuid true)
               :root-dir root-dir
               :ext-props (map-keys xml/as-qname ext-props))]
    (when (seq add-schema)
      (log/info "Adding extra schema" add-schema)
      (let [{db :db-after} @(d/transact conn add-schema)]
        (d/sync-schema conn (d/basis-t db))))
    (-> (fn [req]
          (h (assoc req
                    ::store (store/open-db dstore)
                    ::bstore store)))
        (wd/wrap-execute-tx conn))))

(defn wrap-root [h]
  (fn [req]
    (let [uri-segs (uri-segments req)
          path-segs (path-info-segments req)
          n (- (count uri-segs) (count path-segs))]
      (h (assoc req :root-dir (take n uri-segs))))))

;; Handlers

(def blob-handler
  "Unused for WebDAV, but useful if you want to serve stable, infinitely
  cacheable names, say for a CDN."
  (app
   [] {:post (fn [{body :body store ::bstore :as req}]
               (let [{:keys [tempfile sha] :as res} (blob/store-temp! store #(io/copy body %))
                     created (blob/merge-temp! store res)
                     uri (dav/pjoin (:uri req) sha)]
                 (-> (response sha)
                     (header "location" uri)
                     (header "etag" (str \" sha \"))
                     (status (if created 201 302)))))}
   [sha] {:get (fn [req]
                 (if-let [f (blob/find-blob (::bstore req) sha)]
                   (-> (response f)
                       (content-type "application/octet-stream")
                       (header "etag" (str \" sha \")))
                   (not-found "Blob not found")))}))

(def file-handler
  (app
   wrap-root
   wrap-head
   dav/wrap-errors
   [& path] {:options (dav/options path)
             :propfind (dav/propfind path)
             :get (dav/read path)
             :mkcol (dav/mkcol path)
             :copy (dav/copy path)
             :move (dav/move path)
             :delete (dav/delete path)
             :put (dav/put path)
             :proppatch (dav/proppatch path)
             :lock (dav/lock path)
             :unlock (dav/unlock path)}))

(defn wrap-access-control [h allow-origin]
  (let [hdr {"access-control-allow-origin" allow-origin
             "access-control-allow-credentials" "true"
             "access-control-allow-methods" "OPTIONS,GET,PUT,DELETE,PROPFIND,REPORT,MOVE,COPY,PROPPATCH,MKCOL"
             "access-control-allow-headers" "Content-Type,Depth,Cache-Control,X-Requested-With,If-Modified-Since,If-Match,If,X-File-Name,X-File-Size,Destination,Overwrite,Authorization"
             "dav" "2"}]
    (fn [req]
      (if (= :options (:request-method req))
        {:status 204 :headers hdr}
        (when-let [resp (h req)]
          (update-in resp [:headers] #(merge hdr %)))))))

