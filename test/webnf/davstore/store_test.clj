(ns webnf.davstore.store-test
  (:require [clojure.test :refer :all]
            [webnf.davstore.store :as store]
            [webnf.filestore :as blob]
            [datomic.api :as d]
            [webnf.datomic.query :refer [reify-entity]]
            [clojure.pprint :refer :all]
            [clojure.repl :refer :all]
            [webnf.davstore.util :refer [alias-ns]]))


(alias-ns
 :de  :webnf.davstore.entry
 :det :webnf.davstore.entry.type
 :des :webnf.davstore.entry.snapshot
 :dr  :webnf.davstore.root
 :dd  :webnf.davstore.dir
 :dfc :webnf.davstore.file.content
 :dfn :webnf.davstore.fn)

(defn- exec-tx [store {:keys [webnf.datomic/tx]}]
  @(d/transact (:conn store) tx))

(defn store-tp [s p c]
  (exec-tx s (store/store-tp s p c)))

(defn mkdir! [s p]
  (exec-tx s (store/mkdir! s p)))

(defn touch! [s p m s ms]
  (exec-tx s (store/touch! s p m s ms)))

(defn cp! [s p tp r o]
  (exec-tx s (store/cp! s p tp r o)))

(defn mv! [s p tp r o]
  (exec-tx s (store/mv! s p tp r o)))

(defn rm! [s p m r]
  (exec-tx s (store/rm! s p m r)))

(defn insert-testdata [store]
  (let [e (partial exec-tx store)]
    (e (store-tp store ["a"] "a's content"))
    (e (store-tp store ["b"] "b's content"))
    (e (mkdir! store ["d"]))
    (e (store-tp store ["d" "c"] "d/c's content"))))

(def testdata-ref-tree
  {"a" "a's content"
   "b" "b's content"
   "d" {"c" "d/c's content"}})

(def ^:dynamic *store*)

(use-fixtures
  :once (fn [f]
          (let [uuid (d/squuid)
                db-uri (str "datomic:mem://davstore-test" uuid)
                blob-path (str "/tmp/davstore-test" uuid)]
            (try
              (let [blobstore (blob/make-store! blob-path)]
                (binding [*store* (store/init-store! db-uri blobstore uuid true)]
                  ;; (pprint [:once (dissoc *store* :path-entry)])
                  (insert-testdata *store*)
                  (f)))
              (finally
                (d/delete-database db-uri)
                (org.apache.commons.io.FileUtils/deleteDirectory
                 (java.io.File. blob-path)))))))
(use-fixtures
    :each (fn [f]
            (let [uuid (d/squuid)]
              (binding [*store* (store/open-root! *store* uuid {})]
                ;; (pprint [:each (dissoc *store* :path-entry)])
                 (insert-testdata *store*)
                (f)))))

(defn test-verify-store
  ([] (test-verify-store true))
  ([expect-succcess]
   ;;  (pr-tree *store*)
   (is expect-succcess "TODO: implement checks for store invariants")))

(deftest verification
  (test-verify-store)
  @(d/transact (:conn *store*)
               [[:db/add (:db/id (store/get-entry *store* ["d" "c"]))
                 ::de/name "C"]])
  (test-verify-store false))

(def ^:dynamic ^{:doc "testdata-ref-tree"} *trt*)

(defn store-content [sha1]
  (when-let [f (and sha1 (blob/find-blob (:blob-store *store*) sha1))]
    (slurp f)))

(defn match-tree
  ([[entry]] (match-tree entry *trt*))
  ([{:keys [::dd/children ::dfc/sha-1] :as entry} tree]
   ;(println (reify-entity entry))
   (cond
     (string? tree) (is (= tree (store-content sha-1)))
     (map? tree) (let [ch (into {} (map (juxt ::de/name identity) children))]
                   (is (= (count ch) (count children)) "Multiple entries with same name")
                   (reduce-kv
                    (fn [_ fname content]
                      (let [c (get ch fname)]
                        (is c (str "File `" fname "` not present"))
                        (match-tree c content)))
                    nil tree))
     :else (throw (ex-info "No match" {})))))

(defn match-root
  ([] (match-root *trt*))
  ([tree]
     (match-tree (store/get-entry *store* []) tree)))

(defn update-root! [f]
  (set! *trt* (f *trt*))
  (match-root))

(deftest file-ops
  (binding [*trt* testdata-ref-tree]
    (match-root)
    (testing "Copy file"
      (cp! *store* ["a"] ["A"] false false)
      (update-root! #(assoc % "A" (% "a"))))
    (testing "Copy to existing file, no overwrite"
      (is (thrown? clojure.lang.ExceptionInfo
                   (cp! *store* ["b"] ["A"] false false))))
    (testing "Copy to existing file, overwrite"
      (cp! *store* ["b"] ["A"] false true)
      (update-root! #(assoc % "A" (% "b"))))
    (testing "Copy dir, no recursive"
      (is (thrown? clojure.lang.ExceptionInfo
                   (cp! *store* ["d"] ["D"] false false))))
    (testing "Copy dir, recursive"
     (cp! *store* ["d"] ["D"] true false)
     (update-root! #(assoc % "D" (% "d"))))
    (testing "Copy dir"
      (is (thrown? clojure.lang.ExceptionInfo
                   (cp! *store* ["d"] ["D"] true false)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (cp! *store* ["d"] ["D"] false true)))
      (cp! *store* ["d"] ["D"] true true)
      (match-root))
    (testing "Move file"
      (mv! *store* ["A"] ["A'"] false false)
      (update-root! #(-> % (assoc "A'" (% "A")) (dissoc "A")))
      (is (thrown? clojure.lang.ExceptionInfo
                   (mv! *store* ["b"] ["A'"] false false)))
      (mv! *store* ["a"] ["d" "a"] false false)
      (update-root! #(-> % (assoc-in ["d" "a"] (% "a")) (dissoc "a")))
      (is (thrown? clojure.lang.ExceptionInfo
                   (mv! *store* ["b"] ["d" "a"] false false)))
      (mv! *store* ["b"] ["d" "a"] false true)
      (update-root! #(-> % (assoc-in ["d" "a"] (% "b")) (dissoc "b"))))
    (testing "Move dir"
      (is (thrown? clojure.lang.ExceptionInfo
                   (mv! *store* ["d"] ["D"] true false)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (mv! *store* ["d"] ["D"] false true)))
      (mv! *store* ["d"] ["D"] true true)
      (update-root! #(dissoc % "d")))))

(deftest delete
  (binding [*trt* testdata-ref-tree]
    (rm! *store* ["a"] :current false)
    (update-root! #(dissoc % "a"))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rm! *store* ["d"] :current false)))
    (rm! *store* ["d"] :current true)
    (update-root! #(dissoc % "d"))))

(deftest self-referential
  (binding [*trt* testdata-ref-tree]
    (testing "Copy into itself"
      (cp! *store* ["d"] ["e"] true false)
      (cp! *store* ["d"] ["d" "D"] true false)
      (cp! *store* ["d"] ["d" "E"] true false)
      (cp! *store* ["d"] ["e" "D"] true false)
      (cp! *store* ["d"] ["e" "E"] true false)
      (update-root! #(-> %
                         (assoc "e" (% "d"))
                         (assoc-in ["d" "D"] (% "d"))
                         (assoc-in ["d" "E"] (% "d"))
                         (assoc-in ["e" "D"] (% "d"))
                         (assoc-in ["e" "E"] (% "d")))))
    (testing "Move into itself"
      (is (thrown? clojure.lang.ExceptionInfo
                   (mv! *store* ["d"] ["d" "D" "d'"] true false)))
      (mv! *store* ["d"] ["e" "D" "d'"] true false)
      (update-root! #(-> % (assoc-in ["e" "D" "d'"] (% "d")) (dissoc "d"))))))

(comment

  (def test-db-uuid (d/squuid))
  (def test-db-uri (str "datomic:mem://davstore-test" test-db-uuid))
  (def test-blob-path (str "/tmp/davstore-test/" test-db-uuid))
  (def test-filestore (blob/make-store! test-blob-path))
  (def test-store (store/init-store! test-db-uri test-filestore test-db-uuid true))
  (insert-testdata test-store)
  
  (store/pr-tree test-store)
  (binding [*store* test-store]
    (match-root testdata-ref-tree))
  (store/reify-entity (get-entry test-store ["a"]))

  (cp! test-store ["a"] ["A"] false false)

  )
