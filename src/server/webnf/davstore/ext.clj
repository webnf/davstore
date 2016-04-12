(ns webnf.davstore.ext)

(defprotocol ExtensionProperty
  (xml-content [p entity])
  (db-add-tx [p entity xml-content])
  (db-retract-tx [p entity]))
