(ns webnf.davstore.dav.xml
  (:require [clojure.data.xml :as xml]
            #?@(:clj [[clojure.core.match :refer [match]]
                      [clojure.tools.logging :as log]]
                :cljs [[cljs.core.match :refer-macros [match]]
                       [webnf.base.logging :as log]])))

;; # Namespaced xml parsing

;; ## Set up runtime - global keyword prefixes
;; They can be used to denote namespaced xml names in as regular clojure keywords

(xml/declare-ns
 :webnf.davstore.dav.xml "DAV:"
 :webnf.davstore.ext "urn:webnf:davstore:ext")

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
                   [{:tag ::allprop}]
                   (assoc pm ::all true)
                   [{:tag ::propname}]
                   (assoc pm ::names-only true)
                   [{:tag ::prop
                     :content content}]
                   (reduce (fn [pm {pn :tag pv :content}]
                             (assoc pm pn pv))
                           pm content)
                   :else (error! :no-match (pr-str prop))))
          {} props))

(defn parse-propfind [pf]
  (match [pf]
         [{:tag ::propfind
           :content props}]
         (parse-props props)))

(defn parse-propertyupdate [pu]
  (match [pu]
         [{:tag ::propertyupdate
           :content updates}]
         (reduce
          #(match [%2]
                  [{:tag ::set :content props}] (update %1 :set-props merge (parse-props props))
                  [{:tag ::remove :content props}] (update %1 :remove-props merge (parse-props props)))
          {} updates)))

(defn parse-lock [lock-props]
  (reduce (fn [lm prop]
            (match [prop]
                   [{:tag ::lockscope
                     :content ([scope] :seq)}]
                   (assoc lm :scope (match [scope]
                                           [{:tag ::exclusive}] :exclusive
                                           [{:tag ::shared}] :shared))
                   [{:tag ::locktype
                     :content ([{:tag ::write}] :seq)}]
                   (assoc lm :type :write)
                   [{:tag ::owner
                     :content owner-info}]
                   (assoc lm :owner owner-info)))
          {} lock-props))

(defn parse-lockinfo [li]
  (match [li]
         [{:tag ::lockinfo
           :content lock}]
         (parse-lock lock)))

;; # XML output

(defn emit [xt]
  (xml/emit-str
   (-> xt
       (assoc-in [:attrs :xmlns/d] (xml/ns-uri (str (ns-name *ns*))))
       (assoc-in [:attrs :xmlns/e] (xml/ns-uri "webnf.davstore.ext")))))

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
   (xml/element* ::prop nil
                 (for [[n v] ps
                       :when v]
                   (element n v)))))

#?(:clj (defn- status [code]
          (xml/element ::status nil (if (number? code)
                                      (str "HTTP/1.1 " code " " (get-status-phrase code))
                                      (str code)))))

#?(:clj (defn propstat [& {:as status-props}]
          (reduce-kv (fn [r st ps]
                       (if (and st (seq ps))
                         (conj r (xml/element ::propstat nil
                                              (props ps)
                                              (status st)))
                         r))
                     [] status-props)))

(defn response [href status-or-propstat]
  (xml/element* ::response nil
                (cons (xml/element ::href nil href)
                      (to-multi status-or-propstat))))

(defn multistatus [href-s-o-ps]
  (xml/element* ::multistatus nil
                (for [[href s-o-ps] href-s-o-ps]
                  (response href s-o-ps))))

(defn- dav-prop [kw]
  {:tag (xml/qname "DAV:" (name kw))})

(defn activelock [{:keys [scope type owner depth timeout token]}]
  (element ::activelock
           [(element ::locktype (dav-prop type))
            (element ::lockscope (dav-prop scope))
            (element ::depth depth)
            (element ::owner owner)
            (element ::timeout timeout)
            (element ::locktoken (element ::href (str "urn:uuid:" token)))]))

;; ### client - input elements

(defn propfind
  ([] (propfind ::allprop))
  ([properties]
   (element
    ::propfind
    [(if (= ::allprop props)
       (xml/element ::allprop)
       (xml/element ::prop {} (props properties)))])))

(defn propertyupdate [properties nss]
  (xml/element
   ::propertyupdate {}
   (xml/element
    ::set {}
    (xml/element
     ::prop (reduce-kv (fn [nss pf ns]
                         (assoc nss (keyword "xmlns" pf) ns))
                       {} nss)
     (props properties)))))
