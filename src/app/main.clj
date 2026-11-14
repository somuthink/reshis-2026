(ns app.main
  (:gen-class)
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [integrant.core :as ig]))

(defmethod aero/reader 'ig/ref
  [_ _ value]
  (ig/ref value))

(defn -main [& _]
  (let [raw (-> "config.edn" io/resource aero/read-config)]
    (ig/load-namespaces raw)
    (let [config (ig/expand raw)]
      (ig/load-namespaces config)
      (let [system (ig/init config)]
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. #(ig/halt! system)))))))
