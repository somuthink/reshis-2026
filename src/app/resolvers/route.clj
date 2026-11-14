(ns app.resolvers.route
  (:require [app.acl :as acl]
            [app.resolvers.scenario :as scenario]
            [com.wsscode.pathom3.connect.operation :as pco]
            [eacl.core :as eacl]
            [eacl.datahike.core :as eacl.datahike]
            [datahike.api :as d]
            [integrant.core :as ig]
            [scriptorium.eacl :as se]
            [scriptorium.pathom.params :as params]))

(defn- route-cities
  [env city-attribute other-attribute code]
  (eacl/with-snapshot [snapshot (eacl/snapshot (get-in env [:app.acl/client :client]))]
    (d/q {:find '[[(pull ?city [:city/id]) ...]]
          :in '[$ [?route-id ...] ?city-attribute ?other-attribute ?code]
          :where (cond-> '[[?route :route/id ?route-id]
                           [?route ?city-attribute ?city]]
                   (seq code) (into '[[?route ?other-attribute ?other]
                                      [?other :city/id ?code]]))}
         (eacl.datahike/db snapshot)
         (se/lookup-resource-ids snapshot {:subject (acl/subject-resource env nil)
                                           :permission :view :resource/type :route})
         city-attribute other-attribute (or code ""))))

(pco/defresolver origin-cities
  [env _]
  {::params/schema [:map {:closed true} [:to {:optional true} :string]]
   :eacl/permission :view
   :eacl/resource acl/subject-resource
   ::pco/output [{:route/origin-cities [:city/id]}]}
  (let [{:keys [to]} (pco/params env)]
    {:route/origin-cities (route-cities env :route/origin-city :route/destination-city to)}))

(pco/defresolver destination-cities
  [env _]
  {::params/schema [:map {:closed true} [:from {:optional true} :string]]
   :eacl/permission :view
   :eacl/resource acl/subject-resource
   ::pco/output [{:route/destination-cities [:city/id]}]}
  (let [{:keys [from]} (pco/params env)]
    {:route/destination-cities (route-cities env :route/destination-city :route/origin-city from)}))

(pco/defresolver forecast-dates
  [{:scriptorium.datahike/keys [env->db] :as env} _]
  {::params/schema [:map {:closed true}
                    [:from {:optional true} :string]
                    [:to {:optional true} :string]]
   :eacl/permission :view
   :eacl/resource acl/subject-resource
   ::pco/output [{:route/forecast-dates [:forecast/date]}
                 {:route/selected [:route/id]}]}
  (let [{:keys [from to]} (pco/params env)
        route (when (and (seq from) (seq to))
                (scenario/route-selection env {:scenario/from {:city/id from}
                                               :scenario/to {:city/id to}}))]
    {:route/selected route
     :route/forecast-dates
     (if route
       (mapv (fn [date] {:forecast/date date})
             (sort (d/q '[:find [?date ...]
                          :in $ ?route-id
                          :where [?route :route/id ?route-id]
                          [?route :route/forecasts ?forecast]
                          [?forecast :forecast/date ?date]]
                        (env->db env) (:route/id route))))
       [])}))

(defmethod ig/init-key ::resolvers [_ _]
  [origin-cities destination-cities forecast-dates])
