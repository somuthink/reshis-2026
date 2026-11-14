(ns app.resolvers.user
  (:require [clojure.string :as str]
            [com.wsscode.pathom3.connect.operation :as pco]
            [datahike.api :as d]
            [integrant.core :as ig])
  (:import [java.util Locale]))

(defn canonical-email [email]
  (.toLowerCase (str/trim (if (string? email) email "")) Locale/ROOT))

(defn by-id
  [{:scriptorium.datahike/keys [env->db] :as env} id]
  (when (string? id)
    (d/q '[:find (pull ?user [:user/id :user/email]) .
           :in $ ?id :where [?user :user/id ?id]]
         (env->db env) id)))

(defn by-email
  "Server-only credential lookup; deliberately not a Pathom resolver."
  [{:scriptorium.datahike/keys [env->db] :as env} email]
  (d/q '[:find (pull ?user [:user/id :user/email :user/password-hash]) .
         :in $ ?email :where [?user :user/email ?email]]
       (env->db env) (canonical-email email)))

(defn cities
  [{:scriptorium.datahike/keys [env->db] :as env}]
  (->> (d/q '[:find ?id ?name :where [?e :city/id ?id] [?e :city/name ?name]] (env->db env))
       (map (fn [[id name]] {:city/id id :city/name name}))
       (sort-by (juxt :city/name :city/id))
       vec))

(defn registration-routes
  [{:scriptorium.datahike/keys [env->db] :as env} selected-city-ids]
  (let [selected (set selected-city-ids)]
    (->> (d/q '[:find ?id ?from ?to
                :where [?r :route/id ?id]
                [?r :route/origin-city ?origin] [?origin :city/id ?from]
                [?r :route/destination-city ?destination] [?destination :city/id ?to]]
              (env->db env))
         (keep (fn [[id from to]]
                 (when (and (contains? selected from) (contains? selected to))
                   {:route/id id})))
         (sort-by :route/id)
         vec)))

(pco/defresolver registration-cities [env _]
  {:scriptorium/public? true
   ::pco/output [{:user/registration-cities [:city/id :city/name]}]}
  {:user/registration-cities (cities env)})

(defmethod ig/init-key ::resolvers [_ _]
  [registration-cities])
