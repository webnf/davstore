(ns davstore.dav
  (:require [clojure.data.xml :as xml]
            [clojure.core.match :refer [match]]
            [clojure.tools.logging :as log]
            [davstore.store :as store]
            [davstore.blob :as blob]
            [davstore.dav.xml :as dav]
            [lazymap.core :refer [lazy-hash-map]]
            [webnf.kv :refer [map-vals assoc-when*]]
            [clojure.string :refer [blank? split]]
            [ring.util.response :refer [created]])
  (:import java.net.URI
           java.io.File
           java.nio.file.Files))

(defmacro defhandler [name [route-info-sym request-sym :as args] & body]
  (assert (= 2 (count args)) "Handler must take route-info and request")
  `(defn ~name [~route-info-sym]
     (fn [~request-sym]
       ~@body)))

(defn entry-status [{:as want-props :keys [::dav/all ::dav/names-only]}
                    {:as entry :keys [:davstore.ls/path :davstore.file.content/mime-type :davstore.entry/type
                                      :davstore.file.content/sha-1 :davstore.entry/name :davstore.ls/blob-file]}]
  (let [props (assoc-when* (fn* ([k] (or all (contains? want-props k)))
                                ([k v] (not (nil? v))))
                           {}
                           #xml/name ::dav/displayname name
                           #xml/name ::dav/getcontenttype mime-type
                           #xml/name ::dav/getetag (when sha-1 (str \" sha-1 \"))
                           #xml/name ::dav/resourcetype
                           (case type
                             :davstore.entry.type/container (xml/element ::dav/collection)
                             :davstore.entry.type/file (xml/element ::dav/bendlas:file))
                           #xml/name ::dav/getcontentlength (and blob-file (str (.length blob-file))))]
    (if names-only
      (map-vals (constantly nil) props)
      props)))

(defn pjoin [^String root-dir & [path & pathes]]
  (let [sb (StringBuilder. root-dir)]
    (when-not (= \/ (.charAt root-dir (dec (count root-dir))))
      (.append sb \/))
    (when path
      (.append sb path)
      (reduce (fn [_ p] (.append sb \/) (.append sb p))
              nil pathes))
    (str sb)))

(defn propfind-status [^String root-dir files {:as want-props :keys [::dav/all ::dav/names-only]}]
  (reduce (fn [m {:keys [:davstore.ls/path] :as entry}]
            (assoc m
              (apply pjoin root-dir path)
              (dav/propstat 200 (entry-status want-props entry))))
          {} files))

(def path-matcher
  (memoize (fn [^String root-dir]
             (fn [^String uri]
               (when (zero? (.indexOf uri root-dir))
                 (split (subs uri (count root-dir)) #"/"))))))

(defn to-path [{{:keys [root-dir]
                 :or {root-dir "/"}} :davstore.app/store
                 {host "host"} :headers}
               uri]
  (let [uri* (URI/create uri)
        uhost (.getAuthority uri*)
        path (.getRawPath uri*)]
    (when (and uhost (not= host uhost))
      (throw (ex-info "Cannot manipulate files across hosts"
                      {:error :foreign-file
                       :target uri
                       :host host})))
    (->> (or (seq ((path-matcher root-dir) path))
             (throw (ex-info "Invalid Prefix"
                             {:error :user-error
                              :allowed-root root-dir
                              :request-uri uri})))
         (remove blank?)
         (map #(java.net.URLDecoder/decode % "UTF-8"))
         vec)))

(defn parse-etag [etag]
  (when-let [[_ res] (and etag (re-matches #"\"([^\"]+)\"" etag))]
    res))

(defn infer-type [^File file name]
  (Files/probeContentType (.toPath file)))

;; Handlers

(defhandler options [_ _]
  {:status 200
   :headers {"DAV" "1"}})

(defhandler propfind [path {:as req
                            store :davstore.app/store
                            {depth "depth"} :headers
                            uri :uri}]
  (log/info "PROPFIND" uri (pr-str path) "depth" depth)
  (if-let [fs (seq (store/ls store (remove blank? path) 
                             (case depth
                               "0" 0
                               "1" 1
                               "infinity" 65536)))]
    (let [want-props (dav/parse-propfind (xml/parse (:body req)))]
      {:status 207 :headers {"content-type" "text/xml; charset=utf-8"}
       :body (dav/emit (dav/multistatus
                        (propfind-status (:root-dir store) fs want-props)))})
    {:status 404}))

(defhandler read [path {:as req store :davstore.app/store uri :uri}]
  (log/info "GET" uri (pr-str path))
  (if-let [{:as entry
            :keys [:davstore.file.content/mime-type :davstore.entry/type :davstore.file.content/sha-1]}
           (store/get-entry store (remove blank? path))]
    (if (= :davstore.entry.type/file type)
      {:status 200
       :headers {"Content-Type" mime-type
                 "ETag" (str \" sha-1 \")}
       :body (store/blob-file store entry)}
      {:status 405})
    {:status 400}))

(defhandler mkcol [path {:as req uri :uri store :davstore.app/store}]
  ;; FIXME normalize path for all
  (log/info "MKCOL" uri (pr-str path))
  (store/mkdir! store (remove blank? path))
  (created uri))

(defhandler delete [path {:as req store :davstore.app/store
                          {etag "if-match"
                           depth "depth"} :headers}]
  (log/info "DELETE" (:uri req) (pr-str path))
  (store/rm! store (remove blank? path) (or (parse-etag etag) :current)
             (case depth
               "0" false
               "infinity" true
               nil true))
  {:status 204})

(defhandler move [path {:as req store :davstore.app/store
                        {:strs [depth overwrite destination]} :headers
                        uri :uri}]
  (log/info "MOVE" uri (pr-str path) "to" destination)
  (match [(store/mv! store (remove blank? path)
                     (to-path req destination)
                     (case depth
                       "0" false
                       "infinity" true
                       nil true)
                     (case overwrite
                       "T" true
                       "F" false
                       nil false))]
         [{:success :moved
           :result :overwritten}]
         {:status 204}
         [{:success :moved
           :result :created}]
         {:status 201}))

(defhandler copy [path {:as req store :davstore.app/store
                        {:strs [depth overwrite destination]} :headers
                        uri :uri}]
  (log/info "COPY" uri (pr-str path) "to" destination)
  (match [(store/cp! store (remove blank? path)
                     (to-path req destination)
                     (case depth
                       "0" false
                       "infinity" true
                       nil true)
                     (case overwrite
                       "T" true
                       "F" false
                       nil false))]
         [{:success :moved
           :result :overwritten}]
         {:status 204}
         [{:success :moved
           :result :overwritten}]
         {:status 201}))

(defhandler put [path {:as req store :davstore.app/store
                       body :body
                       {:strs [content-type if-match]} :headers
                       uri :uri}]
  (log/info "PUT" uri (pr-str path))
  (let [blob-sha (blob/store-file (:blob-store store) body)
        path (remove blank? path)
        ctype (if (or (nil? content-type)
                      (= "application/octet-stream" content-type))
                (infer-type (blob/get-file (:blob-store store) blob-sha)
                            (last path))
                content-type)]
    (store/touch! store path
                  ctype
                  blob-sha
                  (or (parse-etag if-match) :current))))

(defn wrap-errors [h]
  (fn [req]
    (try
      (try (h req)
           (catch java.util.concurrent.ExecutionException e
             (throw (.getCause e))))
      (catch clojure.lang.ExceptionInfo e
        (match [(ex-data e)]
               [{:error :cas/mismatch}] {:status 412}
               [{:error :dir-not-empty}] {:status 412}
               [data] (do (log/error e "Unhandled Exception during" (:request-method req) (:uri req)
                                     "\nException Info:" (pr-str data))
                          {:status 500}))))))

