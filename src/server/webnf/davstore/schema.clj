(ns webnf.davstore.schema
  (:import java.util.UUID)
  (:require
   [clojure.data.xml :refer [alias-ns]]
   [datomic.api :as d :refer [tempid]]
   [webnf.datomic :refer [field enum function defn-db]]
   [webnf.datomic.version :as ver]
   [webnf.base :refer [pprint]]))

(alias-ns
 :de  :webnf.davstore.entry
 :det :webnf.davstore.entry.type
 :des :webnf.davstore.entry.snapshot
 :dr  :webnf.davstore.root
 :dd  :webnf.davstore.dir
 :dfc :webnf.davstore.file.content)

(defn-db webnf.davstore.fn/assert-val
  {:requires [[clojure.tools.logging :as log]]}
  [db id attr val]
  (when (if (nil? val)
          (contains? (d/entity db id) attr)
          (not (seq (d/q (conj '[:find ?id :in $ ?id ?val :where]
                               ['?id attr '?val])
                         db id val))))
    (throw (ex-info "CAS error" {:error :cas/mismatch
                                 :in :webnf.davstore.fn/assert-val
                                 :cas/attribute attr
                                 :cas/expected val
                                 :cas/current (get (d/entity db id) attr)}))))

(defn-db webnf.davstore.fn/assert-available
  [db parent name]
  (when-let [[[id]] (seq (d/q '[:find ?id :in $ ?parent ?name :where
                                [?parent ::dd/children ?id]
                                [?id ::de/name ?name]]
                              db parent name))]
    (ex-info "Entry exists"
             {:error :cas/mismatch
              :in :webnf.davstore.fn/assert-available
              :parent parent
              :conflict-entry id
              :cas/attribute ::de/name
              :cas/expected nil
              :cas/current name})))

(def schema-ident :webnf.davstore/schema)
(def schema-version "1.0")

(def schema
  (-> [{:db/id (tempid :db.part/db)
        :db/ident :db.part/davstore.entries
        :db.install/_partition :db.part/db}

       (field "File name of an entity"
              ::de/name :string :index)
       (field "Entry type"
              ::de/type :ref)
       (enum "Directory type" ::det/dir)
       (enum "File type" ::det/file)

       (field "Entry sha-1"
              ::des/sha-1 :string)

       (field "Creation Date" ::de/created :instant)
       (field "Last Modified Date" ::de/last-modified :instant)


       (field "Global identity of a file root"
              ::dr/id :uuid :unique :identity)
       (field "Working directory of root"
              ::dr/dir :ref)
       (field "Current and former entries of root"
              ::dr/all-snapshot-dirs :ref :many)


       (field "Directory entries"
              ::dd/children :ref :many :component)
       (field "Index file of directory"
              ::dd/index-file :string)

       (field "File content sha-1"
              ::dfc/sha-1 :string :index)
       (field "File content type"
              ::dfc/mime-type :string)]
      (into (for [[var-sym the-var] (ns-interns *ns*)
                  :let [entity (:dbfn/entity (meta the-var))]
                  :when entity]
              entity))
      (into (ver/version-tx schema-ident schema-version nil))))

(defn ensure-schema! [conn]
  (ver/ensure-schema! conn schema-ident schema-version schema))

