(ns webnf.davstore.util
  (:import (clojure.lang Namespace)))

#?
(:clj
 (do
   (defn- clj-ns-name [ns]
     (cond (instance? Namespace ns) (ns-name ns)
           (keyword? ns) (name ns)
           :else (str ns)))
   (defn alias-ns
     "Define a clojure namespace alias for shortened keyword and symbol namespaces.
   Similar to clojure.core/alias, but if namespace doesn't exist, it is created.

   ## Example
   (alias-ns :X :foo.bar)
  {:tag ::D/propfind :content []}"
     {:arglists '([& {:as alias-nss}])}
     [& ans]
     (loop [[a n & rst :as ans] ans]
       (when (seq ans)
         (assert (<= 2 (count ans)) (pr-str ans))
         (let [ns (symbol (clj-ns-name n))
               al (symbol (clj-ns-name a))]
           (create-ns ns)
           (alias al ns)
           (recur rst)))))))
