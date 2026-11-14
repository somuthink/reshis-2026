(ns app.acl
  (:require [eacl.core :as eacl]
            [scriptorium.eacl :as se]))

(defn subject-resource
  [env _]
  (let [subject (:scriptorium.eacl/subject env)]
    (when-not (= :user (:type subject))
      (throw (ex-info "Forbidden" {:type :authz/forbidden})))
    subject))

(defn can-view?
  [{:app.acl/keys [client] :as env} entity]
  (let [subject (:scriptorium.eacl/subject env)
        resource (se/resource (:registry client) entity)]
    (boolean (and subject resource
                  (eacl/can? (:client client) subject :view resource)))))

(defn require-view!
  [env entity]
  (when-not (can-view? env entity)
    (throw (ex-info "Forbidden" {:type :authz/forbidden})))
  entity)

(defn grant-view!
  "Write read-only grants from trusted registration code, not a graph operation."
  [{:app.acl/keys [client]} user resources]
  (let [registry (:registry client)
        subject (se/resource registry user)
        resources (mapv #(se/resource registry %) resources)]
    (when-not (and (= :user (:type subject))
                   (every? #(#{:city :route} (:type %)) resources))
      (throw (ex-info "Invalid registration grant." {:user user :resources resources})))
    (when (seq resources)
      (eacl/write-relationships!
       (:client client)
       {:updates (mapv (fn [resource]
                         {:operation :touch
                          :relationship (eacl/->Relationship subject :viewer resource)})
                       resources)}))))

