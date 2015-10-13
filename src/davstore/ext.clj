(ns davstore.ext)

(defprotocol ExtensionProperty
  (xml-content [p entity])
  (db-tx [p xml-content]))
