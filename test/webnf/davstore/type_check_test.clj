(ns webnf.davstore.type-check-test
  (:require [clojure.core.typed :refer [check-ns]]
            [clojure.test :refer :all]))

(defn check [ns]
  (is (= :ok (check-ns ns)) (str "Typecheck for " ns)))

#_(deftest main-typecheck
    (check 'webnf.davstore.store))

