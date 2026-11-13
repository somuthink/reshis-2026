(ns app.resolvers.route
  (:require [app.resolvers.scenario :as scenario]
            [com.wsscode.pathom3.connect.operation :as pco]
            [datahike.api :as d]
            [integrant.core :as ig]
            [scriptorium.pathom.params :as params]))

(defn- route-cities [db city-attribute other-attribute code]
  (d/q {:find '[[(pull ?city [:city/id :city/name]) ...]]
        :in '[$ ?city-attribute ?other-attribute ?code]
        :where (cond-> '[[?route ?city-attribute ?city]]
                 (seq code) (into '[[?route ?other-attribute ?other]
                                    [?other :city/id ?code]]))}
       db city-attribute other-attribute (or code "")))

(pco/defresolver origin-cities
  [{:scriptorium.datahike/keys [env->db] :as env} _]
  {::params/schema [:map {:closed true} [:to {:optional true} :string]]
   ::pco/output [{:route/origin-cities [:city/id :city/name]}]}
  (let [{:keys [to]} (pco/params env)]
    {:route/origin-cities (route-cities (env->db env) :route/origin-city :route/destination-city to)}))

(pco/defresolver destination-cities
  [{:scriptorium.datahike/keys [env->db] :as env} _]
  {::params/schema [:map {:closed true} [:from {:optional true} :string]]
   ::pco/output [{:route/destination-cities [:city/id :city/name]}]}
  (let [{:keys [from]} (pco/params env)]
    {:route/destination-cities (route-cities (env->db env) :route/destination-city :route/origin-city from)}))

(pco/defresolver forecast-dates
  [{:scriptorium.datahike/keys [env->db] :as env} _]
  {::params/schema [:map {:closed true}
                    [:from {:optional true} :string]
                    [:to {:optional true} :string]]
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
