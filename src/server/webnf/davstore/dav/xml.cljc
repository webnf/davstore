(ns webnf.davstore.dav.xml
  (:require [clojure.data.xml :as xml]
            #?@(:clj [[clojure.core.match :refer [match]]
                      [clojure.tools.logging :as log]]
                :cljs [[cljs.core.match :refer-macros [match]]
                       [webnf.base.logging :as log]])
            [#xml/ns "DAV:" :as dav]
            [#xml/ns "urn:webnf:davstore:ext" :as ext]))

;; # Namespaced xml parsing

;; ## Set up runtime - global keyword prefixes
;; They can be used to denote namespaced xml names in as regular clojure keywords

#_(xml/alias-uri
   :dav "DAV:"
   :ext "urn:webnf:davstore:ext")

(defn multi? [v]
  (or (list? v) (vector? v)))

(defn to-multi [v]
  (if (multi? v) v [v]))

(defn error! [& {:as attrs}]
  (throw (ex-info (str "XML parsing error " attrs) attrs)))

;; ### server - input elements

(defn parse-props [props]
  (reduce (fn [pm prop]
            (match [prop]
                   [{:tag ::dav/allprop}]
                   (assoc pm ::dav/allprop true)
                   [{:tag ::dav/propname}]
                   (assoc pm ::dav/propname true)
                   [{:tag ::dav/prop
                     :content content}]
                   (reduce (fn [pm {pn :tag pv :content}]
                             (assoc pm pn pv))
                           pm content)
                   :else (error! :no-match (pr-str prop))))
          {} props))

(defn parse-propfind [pf]
  (match [pf]
         [{:tag ::dav/propfind
           :attrs attrs
           :content props}]
         (assoc (parse-props props)
                ::ext/propfind.attrs attrs)))

(defn parse-propertyupdate [pu]
  (match [pu]
         [{:tag ::dav/propertyupdate
           :content updates}]
         (reduce
          #(match [%2]
                  [{:tag ::dav/set :content props}] (update %1 :set-props merge (parse-props props))
                  [{:tag ::dav/remove :content props}] (update %1 :remove-props merge (parse-props props)))
          {} updates)))

(defn parse-lock [lock-props]
  (reduce (fn [lm prop]
            (match [prop]
                   [{:tag ::dav/lockscope
                     :content ([scope] :seq)}]
                   (assoc lm :scope (match [scope]
                                           [{:tag ::dav/exclusive}] :exclusive
                                           [{:tag ::dav/shared}] :shared))
                   [{:tag ::dav/locktype
                     :content ([{:tag ::dav/write}] :seq)}]
                   (assoc lm :type :write)
                   [{:tag ::dav/owner
                     :content owner-info}]
                   (assoc lm :owner owner-info)))
          {} lock-props))

(defn parse-lockinfo [li]
  (match [li]
         [{:tag ::dav/lockinfo
           :content lock}]
         (parse-lock lock)))

;; # XML output

(defn emit [xt]
  (xml/emit-str
   (-> xt
       (assoc-in [:attrs :xmlns/d] "DAV:")
       (assoc-in [:attrs :xmlns/e] "urn:webnf:davstore:ext"))))

#?(:clj (def get-status-phrase
          (into {}
                (for [^java.lang.reflect.Field f (.getFields java.net.HttpURLConnection)
                      :let [name (.getName f)]
                      :when (.startsWith name "HTTP_")
                      :let [code (.get f java.net.HttpURLConnection)]]
                  [code name]))))

(defn- element [name content]
  (xml/element* name nil (to-multi content)))

(defn props [ps]
  (#?(:clj  xml/aggregate-xmlns
      ;; FIXME cljs support for processing
      :cljs identity)
   (xml/element* ::dav/prop nil
                 (for [[n v] ps
                       :when v]
                   (element n v)))))

(defn- status [code]
  (xml/element ::dav/status nil (if (number? code)
                              #?(:clj  (str "HTTP/1.1 " code " " (get-status-phrase code))
                                 :cljs (str "HTTP/1.1 " code))
                              (str code))))

(defn multistatus [href-propstats]
  (xml/element* ::dav/multistatus nil
                (for [[href st-pr] href-propstats]
                  (xml/element* ::dav/response nil
                                (cons (xml/element ::dav/href nil href)
                                      (if (number? st-pr)
                                        [(status st-pr)]
                                        (for [[st pr] st-pr]
                                          (xml/element ::dav/propstat {}
                                                       (props pr) (status st)))))))))

(defn- dav-prop [kw]
  {:tag (xml/qname "DAV:" (name kw))})

(defn activelock [{:keys [scope type owner depth timeout token]}]
  (element ::dav/activelock
           [(element ::dav/locktype (dav-prop type))
            (element ::dav/lockscope (dav-prop scope))
            (element ::dav/depth depth)
            (element ::dav/owner owner)
            (element ::dav/timeout timeout)
            (element ::dav/locktoken (element ::dav/href (str "urn:uuid:" token)))]))

;; ### client - input elements

(defn propfind
  ([] (propfind ::dav/allprop))
  ([properties]
   (element
    ::dav/propfind
    [(if (= ::dav/allprop props)
       (xml/element ::dav/allprop)
       (xml/element ::dav/prop {} (props properties)))])))

(defn propertyupdate [properties nss]
  (xml/element
   ::dav/propertyupdate {}
   (xml/element
    ::dav/set {}
    (xml/element
     ::dav/prop (reduce-kv (fn [nss pf ns]
                         (assoc nss (keyword "xmlns" pf) ns))
                       {} nss)
     (props properties)))))
