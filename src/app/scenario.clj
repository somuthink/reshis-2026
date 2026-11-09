(ns app.scenario
  (:require [com.wsscode.pathom3.connect.operation :as pco]
            [integrant.core :as ig]
            [scriptorium.pathom.params :as params]))

(pco/defmutation evaluate
  [_ {:keys [from to date price]}]
  {::params/schema [:map {:closed true}
                    [:from [:string {:min 1}]]
                    [:to [:string {:min 1}]]
                    [:date [:re "[0-9]{4}-[0-9]{2}-[0-9]{2}"]]
                    [:price [:or [:int {:min 0}]
                             [:double {:min 0 :max 1.7976931348623157E308}]]]]
   ::pco/output [:scenario/id {:scenario/from [:city/id]}
                 {:scenario/to [:city/id]} :scenario/date :scenario/price]}
  {:scenario/id "current"
   :scenario/from {:city/id from} :scenario/to {:city/id to}
   :scenario/date date :scenario/price price})

(defmethod ig/init-key ::mutations [_ _]
  [evaluate])

(defn estimate-sales
  [median-demand seats base-price sensitivity price]
  (let [demand (- median-demand (* sensitivity (- price base-price)))]
    {:demand demand :sales (min (double seats) (max 0.0 demand))}))

(defn estimate
  [{:scenario/keys [from to date price route forecast]}]
  (let [{:route/keys [name base-price price-sensitivity]} route
        {:forecast/keys [seats demand-q50]} forecast]
    (cond
      (nil? route) {:error (str "No route exists from " (:city/id from) " to " (:city/id to) ".")}
      (nil? forecast) {:error (str "No forecast exists for " date " on route " name ".")}
      :else (merge {:name name :date date :price price :median-demand demand-q50 :seats seats}
                   (estimate-sales demand-q50 seats base-price price-sensitivity price)))))
