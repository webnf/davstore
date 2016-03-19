(ns davstore.client.dav
  (:require
   [cljs.core.async :refer [map< <! >! chan]]
   [clojure.string :as str]
   [goog.dom :as gdom]
   [goog.object :as gob]
   [webnf.util :refer [log log-pr xhr hmap]]
   [cljs.core.match :refer-macros [match]]
   [cljs.pprint :refer [pprint]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn- dav-request-fn [req-fn on-auth]
  (go (let [first-resp (<! (req-fn {}))]
        (if (and on-auth (= 401 (:status first-resp)))
          (let [[ah ahv] (<! (on-auth first-resp))]
            (<! (req-fn {ah ahv})))
          first-resp))))

(defn- to-path [href]
  (let [p (str/split href #"/")]
    (assert (= "" (first p)))
    (vec (rest p))))

(defn- to-href [path]
  (str "/" (str/join "/" path)))

(defn subpath [path & sub]
  (assert (or (vector? path)
              ;;cursors
              (vector? (.-value path))))
  (into path sub))

(defn proplist [uri root-path & {:keys [depth on-auth add-headers]}]
  (let [full-uri (str uri (to-href root-path))]
    (dav-request-fn
     (fn [add-headers*]
       (xhr full-uri
            {:method "PROPFIND"
             :headers (merge {"content-type" "application/xml"
                              "depth" (if depth (str depth) "infinity")}
                             add-headers
                             add-headers*)
             :body "<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><allprop/></propfind>"
             :xml true}))
     on-auth)))

(defn tag= [ns-uri local-name]
  (fn [el]
    (and (= ns-uri (.-namespaceURI el))
         (= local-name (.-localName el)))))

(def multistatus? (tag= "DAV:" "multistatus"))
(def response? (tag= "DAV:" "response"))
(def propstat? (tag= "DAV:" "propstat"))
(def href? (tag= "DAV:" "href"))
(def prop? (tag= "DAV:" "prop"))
(def status? (tag= "DAV:" "status"))

(defmulti parse-dav-response
  (fn [{:keys [uri status headers body]}]
    status))

(defn- prop-el-value [el]
  (match [(.-namespaceURI el) (.-localName el)]
         ["DAV:" "resourcetype"] (let [cn (.-childNodes el)
                                       ch (aget cn 0)]
                                   (assert (= 1 (alength cn)))
                                   (match [(.-namespaceURI ch) (.-localName ch)]
                                          ["DAV:" "collection"] :collection
                                          ["//dav.bendlas.net/extension-elements" "file"] :file))
         ["DAV:" "getcontentlength"] (js/parseInt (.-textContent el))
         ["DAV:" (:or "getlastmodified" "creationdate")] (js/Date. (.-textContent el))
         :else (.-textContent el)))

(defn- parse-prop-el* [el ret]
  (let [cn (.-childNodes el)]
    (areduce cn i ret ret
             (let [ch (aget cn i)]
               (assoc! ret
                       (match [(.-namespaceURI ch) (.-localName ch)]
                              ["DAV:" name] (keyword "dav" name)
                              [ns name] (str \{ ns \} name))
                       (prop-el-value ch))))))

(defn- parse-status [s]
  (if-let [[_ code] (re-matches #"HTTP/\S+ (\S+) \S+" s)]
    (js/parseInt code)
    (throw (ex-info "Invalid status" {:status s}))))

(defn- parse-propstat-el* [el ret]
  (let [cn (.-childNodes el)]
    (areduce cn i ret ret
             (let [ch (aget cn i)]
               (cond (status? ch)
                     (assoc! ret :status (parse-status (.-textContent ch)))
                     (prop? ch)
                     (parse-prop-el* ch ret)
                     :else (do (log "WARN" "Unknown propstat element" ch)
                               ret))))))

(defn- parse-response-el [el]
  (let [cn (.-childNodes el)]
    (persistent!
     (areduce cn i ret (transient {})
              (let [ch (aget cn i)]
                (cond (href? ch)
                      (-> ret
                          (assoc! :href (.-textContent ch))
                          (assoc! :path (to-path (.-textContent ch))))
                      (propstat? ch)
                      (parse-propstat-el* ch ret)
                      :else (do (log "WARN" "Unknown response element" ch)
                                ret)))))))

(defmethod parse-dav-response 207 [{:keys [uri status headers body]}]
  (let [ms (.-documentElement body)
        cn (.-childNodes ms)]
    (assert (multistatus? ms))
    (persistent!
     (areduce cn i ret (transient [])
              (let [rsp (aget cn i)]
                (assert (response? rsp))
                (conj! ret (parse-response-el rsp)))))))

(defmethod parse-dav-response 204 [resp]
  (assoc resp ::result :no-content))
(defmethod parse-dav-response 201 [resp]
  (assoc resp ::result :created))

(defmethod parse-dav-response :default [resp]
  (assoc resp ::error :unexpected-status))

(defn into-tree [tree listing]
  (reduce (fn [t {:as node path :path}]
            (assoc-in t (conj path nil) node))
          tree listing))

(defn tree [uri root-path on-auth]
  (go
    (with-meta
      (into-tree
       {} (parse-dav-response
           (<! (proplist uri root-path
                         :on-auth on-auth))))
      {:uri uri :root-path root-path :on-auth on-auth})))

(defn subtree [tree path]
  (go
    (let [{:keys [uri root-path on-auth]} (meta tree)]
      (assert (= root-path (take (count root-path) path)))
      (with-meta
        (get-in (into-tree
                 {} (parse-dav-response
                     (<! (proplist uri path :on-auth on-auth))))
                path)
        {:uri uri :root-path path :on-auth on-auth}))))

(comment
  (defn test-tree []
    (tree "http://static.bendlas.net" "/jakob-kapeller.org"
          (fn [req]
            (go ["authorization" "Basic aGVyd2lnQGJlbmRsYXMubmV0OkhGVmJ4ZmFu"]))))
  (go (def dav-tree
        (<! (test-tree)))))


(defn get-path [path on-auth]
  (map<
   :body
   (dav-request-fn
    (fn [add-headers]
      (xhr
       (str server-url "/" (str/join "/" path))
       {:headers add-headers
        :auto-refresh true}))
    on-auth)))

(defn mkcol! [tree path]
  (let [{:keys [uri root-path on-auth]} (meta tree)]
    (assert (= root-path (take (count root-path) path)))
    (dav-request-fn
     (fn [add-headers*]
       (xhr (str uri (to-href path))
            {:method "MKCOL"
             :headers add-headers*}))
     on-auth)))

(defn dummy-file [path content-type]
  {nil {:href (to-href path)
        :path path
        :dav/displayname (last path)
        :dav/getcontenttype content-type}})

(defn put! [tree entry new-content]
  (let [{:keys [dav/getcontenttype
                dav/getetag]}
        entry
        {:keys [uri root-path on-auth]} (meta tree)]
    (assert (= root-path (take (count root-path) (:path entry))))
    (dav-request-fn
     (fn [add-headers*]
       (xhr (str uri (to-href (:path entry)))
            {:method "PUT"
             :headers (-> add-headers*
                          (assoc "Content-Type" getcontenttype)
                          (cond-> getetag (assoc "If-Match" getetag)))
             :body new-content}))
     on-auth)))

(def error ::error)
(def result ::result)

(defn delete! [tree path & [getetag]]
  (let [{:keys [uri root-path on-auth]} (meta tree)]
    (map< parse-dav-response (dav-request-fn
                              (fn [add-headers*]
                                (xhr (str uri (to-href path))
                                     {:method "DELETE"
                                      :headers (-> add-headers*
                                                   (cond-> getetag (assoc "If-Match" getetag))
                                                   (assoc "Depth" "0"))}))
                              on-auth))))
