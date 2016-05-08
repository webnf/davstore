(ns webnf.davstore.ext)

#?
(:clj
 (defprotocol ExtensionProperty
   (xml-content [p entity])
   (db-add-tx [p entity xml-content])
   (db-retract-tx [p entity])))
