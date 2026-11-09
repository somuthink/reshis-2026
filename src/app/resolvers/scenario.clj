(ns app.resolvers.scenario
  (:require [datahike.api :as d]))

(defn- unique-match [matches]
  (when (next matches)
    (throw (ex-info "Ambiguous scenario selection" {:matches matches})))
  (let [[match] matches] match))

(defn route-selection
  [{:scriptorium.datahike/keys [env->db] :as env} {:scenario/keys [from to]}]
  (unique-match
   (d/q '[:find [(pull ?route [:route/id]) ...]
          :in $ ?from ?to
          :where [?origin :city/id ?from] [?destination :city/id ?to]
          [?route :route/origin-city ?origin] [?route :route/destination-city ?destination]]
        (env->db env) (:city/id from) (:city/id to))))

(defn forecast-selection
  [{:scriptorium.datahike/keys [env->db] :as env} {:scenario/keys [date route]}]
  (when route
    (unique-match
     (d/q '[:find [(pull ?forecast [:forecast/id]) ...]
            :in $ ?route-id ?date
            :where [?route :route/id ?route-id]
            [?route :route/forecasts ?forecast] [?forecast :forecast/date ?date]]
          (env->db env) (:route/id route) date))))
