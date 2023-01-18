(ns webnf.davstore.dav
  (:require [clojure.core.match :refer [match]]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [webnf.filestore :as blob]
            [webnf.davstore.dav.xml :as davx]
            (webnf.davstore
             [store :as store]
             [ext :as extp])
            [datomic.api :as d]
            [ring.util.response :refer [created]]
            [webnf.base :refer [pprint-str]]
            [webnf.kv :refer [map-vals assoc-when* treduce-kv]]
            [webnf.date :as date]
            [webnf.davstore.app :as-alias app]
            [webnf.davstore.entry :as-alias de]
            [webnf.davstore.entry.type :as-alias det]
            [webnf.davstore.entry.snapshot :as-alias des]
            [webnf.davstore.root :as-alias dr]
            [webnf.davstore.dir :as-alias dd]
            [webnf.davstore.file.content :as-alias dfc]
            [webnf.davstore.fn :as-alias dfn])
  (:import java.io.File
           java.net.URI
           java.net.URLEncoder
           java.nio.file.Files
           java.util.Date))

(xml/alias-uri
 :dav "DAV:"
 :ext "urn:webnf:davstore:ext")

(defn entry-status [{:as want-props :keys [::dav/allprop ::dav/propname]}
                    {:keys [path blob-file]
                     {:as entry
                      :keys [::dfc/mime-type ::de/type ::dfc/sha-1
                             ::de/name ::de/created ::de/last-modified]}
                     :entity}
                    extension-props]
  (let [props* (assoc-when* (fn* ([k] (or allprop (contains? want-props k)))
                                 ([k v] (not (nil? v))))
                 {}
                 ::dav/displayname name
                 ::dav/getcontenttype mime-type
                 ::dav/getetag (when sha-1 (str \" sha-1 \"))
                 ::dav/getlastmodified (date/format-http (or last-modified (Date. 0)))
                 ::dav/creationdate (date/format-http (or created (Date. 0)))
                 ::dav/resourcetype
                 (case type
                   ;; here you can see, how to refer to xml names externally
                   ::det/dir (xml/element ::dav/collection)
                   ;; some may misinterpret this as directory
                   ::det/file nil #_(xml/element ::ext/file))
                 ::dav/getcontentlength (and blob-file (str (.length ^File blob-file))))
        props (treduce-kv (fn [tr qname ext-prop]
                            (assoc! tr qname (extp/xml-content ext-prop entry)))
                          props* extension-props)]
    (if propname
      (map-vals (constantly nil) props)
      props)))

(defn pjoin [^String root-dir & [path & pathes]]
  (let [sb (StringBuilder. root-dir)]
    (when-not (= \/ (.charAt root-dir (dec (count root-dir))))
      (.append sb \/))
    (when path
      (.append sb (URLEncoder/encode path "UTF-8"))
      (reduce (fn [_ p]
                (.append sb \/)
                (.append sb (URLEncoder/encode p "UTF-8")))
              nil pathes))
    (str sb)))

(defn propfind-status [^String root-dir files {:as want-props :keys [::dav/all ::dav/names-only]} extension-elements]
  (reduce (fn [m {:keys [path] :as entry}]
            (assoc m
                   (apply pjoin root-dir path)
                   {200 (entry-status want-props entry extension-elements)}))
          {} files))

(def path-matcher
  (memoize (fn [^String root-dir]
             (fn [^String uri]
               (when (zero? (.indexOf uri root-dir))
                 (str/split (subs uri (count root-dir)) #"/"))))))

(defn to-path [{{:keys [root-dir]
                 :or {root-dir "/"}} ::app/store
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
         (remove str/blank?)
         (map #(java.net.URLDecoder/decode % "UTF-8"))
         vec)))

(defn parse-etag [etag]
  (when-let [[_ res] (and etag (re-matches #"\"([^\"]+)\"" etag))]
    res))

(def mime-overrides
  {"css" "text/css"
   "js" "text/javascript"})

(defn infer-type [^File file name]
  (let [ext (last (str/split name #"\."))]
    (or (mime-overrides ext)
        (Files/probeContentType (.toPath file)))))

(defmacro defhandler [name [route-info-sym request-sym :as args] & body]
  (assert (= 2 (count args)) "Handler must take route-info and request")
  `(defn ~name [~route-info-sym]
     (fn [~request-sym]
       ~@body)))

;; Handlers

(defhandler options [_ _]
  {:status 204
   :headers {"dav" "1"}})

#_(defn incremental-body [store since-t]
  (let [db (store/store-db store)
        t (d/basis-t db)
        listeners (:listeners store)]
    (if (> t since-t)
      (comment incremental body)
      {:body (fn [ctx]
               (let [state (ref :waiting)
                     age (agent nil)]
                 (dosync (commute listeners assoc ctx))
                 {:error (fn [e]
                           (log/error "Listener crashed" e)
                           (dosync (case (ensure state)
                                     :waiting (do (ref-set state :closed)
                                                  (commute listeners dissoc ctx)
                                                  (send age (fn [_]
                                                              (as/status ctx 500)
                                                              (as/complete ctx)))))))
                  :timeout (fn [e]
                             (log/debug "Listener went away" e))
                  :complete (fn [e]
                              (log/trace "Listener completed" e))}))})))

(defhandler propfind [path {:as req
                            store ::app/store
                            {:strs [depth content-length]} :headers
                            uri :uri}]
                                        ;  (log/info "PROPFIND" uri (pr-str path) "depth" depth)
  (let [want-props (if (= "0" content-length)
                     {::dav/all true}
                     (davx/parse-propfind (xml/parse (:body req) :include-node? #{:element})))
        {:keys [::ext/as-of ::ext/incremental-since]} (::ext/propfind.attrs want-props)]
    (log/info "PROPFIND" (pr-str path) (pr-str want-props))
    ;; BEWARE, stateful ordering
    (let [db (store/store-db store)
          store (assoc store :db (cond-> db as-of (d/as-of (Long/parseLong as-of))))]
      #_(if incremental-since
          (let [since-t (Long/parseLong incremental-since)]
            (incremental-body store db since-t)))
      (if-let [fs (seq (store/ls store
                                 (remove str/blank? path)
                                 (case depth
                                   "0" 0
                                   "1" 1
                                   ;; FIXME: apparently the revised value of infinity is now 18446744073709551616
                                   "infinity" 65536)))]
        {:status 207 :headers {"content-type" "text/xml; charset=utf-8" "dav" "1"}
         :body (-> (propfind-status (:root-dir store) fs want-props (:ext-props store))
                   davx/multistatus
                   (assoc-in [:attrs ::ext/as-of] (str (d/basis-t db)))
                   davx/emit)}
        {:status 404}))))

(defhandler read [path {:as req store ::app/store uri :uri}]
;  (log/info "GET" uri (pr-str path))
  (let [db (store/store-db store)]
    (loop [{:as entry
            :keys [::dfc/mime-type ::de/type ::dfc/sha-1 ::dd/index-file db/id]}
           (store/get-entry store (remove str/blank? path))]
      (if entry
        (if (= ::det/file type)
          {:status 200
           :headers {"Content-Type" mime-type
                     "ETag" (str \" sha-1 \")}
           :body (store/blob-file store entry)}
          (if index-file
            (recur (d/entity db (store/dir-child db id index-file)))
            {:status 405 :body (str uri " is a directory")}))
        {:status 404 :body (str "File " uri " not found")}))))

(defhandler mkcol [path {:as req uri :uri store ::app/store}]
  ;; FIXME normalize path for all
                                        ; (log/info "MKCOL" uri (pr-str path))
  (merge (store/mkdir! store (remove str/blank? path))
         (created uri)))

(defhandler delete [path {:as req store ::app/store
                          {etag "if-match"
                           depth "depth"} :headers}]
                                        ;  (log/info "DELETE" (:uri req) (pr-str path))
  (merge (store/rm! store (remove str/blank? path) (or (parse-etag etag) :current)
                    (case depth
                      "0" false
                      "infinity" true
                      nil true))
         {:status 204}))

(defhandler move [path {:as req store ::app/store
                        {:strs [depth overwrite destination]} :headers
                        uri :uri}]
                                        ;  (log/info "MOVE" uri (pr-str path) "to" destination)
  (let [result (store/mv! store (remove str/blank? path)
                          (to-path req destination)
                          (case depth
                            "0" false
                            "infinity" true
                            nil true)
                          (case overwrite
                            "T" true
                            "F" false
                            nil false))]
    (merge result
           (match [result]
                  [{:success :moved
                    :result :overwritten}]
                  {:status 204}
                  [{:success :moved
                    :result :created}]
                  (created destination)))))

(defhandler copy [path {:as req store ::app/store
                        {:strs [depth overwrite destination]} :headers
                        uri :uri}]
                                        ;  (log/info "COPY" uri (pr-str path) "to" destination)
  (let [result (store/cp! store (remove str/blank? path)
                          (to-path req destination)
                          (case depth
                            "0" false
                            "infinity" true
                            nil true)
                          (case overwrite
                            "T" true
                            "F" false
                            nil false))]
    (merge result
           (match [result]
                  [{:success :copied
                    :result :overwritten}]
                  {:status 204}
                  [{:success :copied
                    :result :created}]
                  (created destination)))))

(defhandler put [path {:as req store ::app/store
                       body :body
                       {:strs [content-type if-match]} :headers
                       uri :uri}]
                                        ;  (log/info "PUT" uri (pr-str path))
  (let [blob-sha (blob/stream-copy-blob! (:blob-store store) body)
        path (remove str/blank? path)
        fname (last path)
        ctype (if (or (nil? content-type)
                      (= "application/octet-stream" content-type))
                (infer-type (blob/get-blob (:blob-store store) blob-sha)
                            fname)
                content-type)
        result (store/touch! store path
                             ctype
                             blob-sha
                             (or (parse-etag if-match) :current))]
    (merge result
           (match [result]
                  [{:success :updated}] {:status 204}
                  [{:success :created}] (created uri)))))

(defhandler proppatch [path {:as req store ::app/store
                             body :body}]
  (let [prop-updates (davx/parse-propertyupdate (xml/parse body))
        result (store/propertyupdate! store path prop-updates)]
    (merge result
           (match [result]
                  [{:status :not-found}] {:status 404}
                  [{:status :multi :propstat propstat}]
                  {:status 207 :headers {"content-type" "text/xml; charset=utf-8" "dav" "1"}
                   :body (davx/emit (davx/multistatus
                                     {(apply pjoin (:root-dir store) path)
                                      {200 (->> propstat
                                                (filter (comp #{:ok} :status))
                                                (map #(vector (:prop %) [])))
                                       404 (->> propstat
                                                (filter (comp #{:not-found} :status))
                                                (map #(vector (:prop %) [])))}}))}))))

(defhandler lock [path {:as req store ::app/store
                        {:strs [depth]} :headers
                        body :body}]
  (let [entry (store/get-entry store (remove str/blank? path))
        info (davx/parse-lockinfo (xml/parse body))]
    ;; (log/debug "Lock Info\n" (pprint-str info))
    {:status (if entry 200 201)
     :body (davx/emit
            (davx/props
             {::dav/lockdiscovery
              (davx/activelock (assoc info
                                      :depth depth
                                      :timeout "Second-60"
                                      :token (java.util.UUID/randomUUID)))}))}))

(defhandler unlock [path {:as req store ::app/store
                          {:strs [lock-token]} :headers}]
  ;; (log/info "unlock token" lock-token)
  {:status 204})

(defn wrap-errors [h]
  (fn [req]
    (try
      (try (h req)
           (catch java.util.concurrent.ExecutionException e
             (throw (.getCause e))))
      (catch clojure.lang.ExceptionInfo e
        (log/debug e "Translating to status code" (pprint-str (ex-data e)))
        (match [(ex-data e)]
               [{:error :cas/mismatch}] {:status 412 :body "Precondition failed"}
               [{:error :dir-not-empty}] {:status 412 :body "Directory not empty"}
               [data] (do (log/error e "Unhandled Exception during" (:request-method req) (:uri req)
                                     "\nException Info:" (pr-str data))
                          {:status 500}))))))
