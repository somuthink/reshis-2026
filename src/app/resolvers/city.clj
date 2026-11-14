(ns app.resolvers.city
  (:require [app.acl :as acl]
            [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.core :as eacl.datahike]
            [scriptorium.eacl :as se]))

(defn- routes
  [env city-id attribute]
  (eacl/with-snapshot [snapshot (eacl/snapshot (get-in env [:app.acl/client :client]))]
    (d/q '[:find [(pull ?route [:route/id]) ...]
           :in $ [?route-id ...] ?city-id ?attribute
           :where [?route :route/id ?route-id]
           [?city :city/id ?city-id] [?route ?attribute ?city]]
         (eacl.datahike/db snapshot)
         (se/lookup-resource-ids snapshot {:subject (acl/subject-resource env nil)
                                           :permission :view :resource/type :route})
         city-id attribute)))

(defn outgoing-routes
  [env {:city/keys [id]}]
  (routes env id :route/origin-city))

(defn incoming-routes
  [env {:city/keys [id]}]
  (routes env id :route/destination-city))
