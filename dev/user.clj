(ns user
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [integrant.repl :as ig-repl :refer [go halt reset]]))

(defmethod aero/reader 'ig/ref
  [_ _ value]
  (ig/ref value))

(defn read-config []
  (let [raw (-> "config.edn" io/resource aero/read-config)]
    (ig/load-namespaces raw)
    (let [expanded (ig/expand raw)]
      (ig/load-namespaces expanded)
      expanded)))

(ig-repl/set-prep! read-config)
