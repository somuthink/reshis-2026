(ns app.mutations.scenario
  (:require [app.acl :as acl]
            [app.resolvers.scenario :as scenario]
            [com.wsscode.pathom3.connect.operation :as pco]
            [integrant.core :as ig]
            [scriptorium.pathom.params :as params]))

(pco/defmutation evaluate
  [env {:keys [from to date price]}]
  {::params/schema [:map {:closed true}
                    [:from [:string {:min 1}]]
                    [:to [:string {:min 1}]]
                    [:date [:re "[0-9]{4}-[0-9]{2}-[0-9]{2}"]]
                    [:price [:or [:int {:min 0}]
                             [:double {:min 0 :max 1.7976931348623157E308}]]]]
   :eacl/permission :view
   :eacl/resource acl/subject-resource
   ::pco/output [:scenario/id {:scenario/from [:city/id]}
                 {:scenario/to [:city/id]} :scenario/date :scenario/price]}
  (let [date (try (java.time.LocalDate/parse date)
                  (catch java.time.format.DateTimeParseException error
                    (throw (ex-info "Invalid flight date"
                                    {:type :error/invalid-expression} error))))
        selection {:scenario/id "current"
                   :scenario/from {:city/id from} :scenario/to {:city/id to}
                   :scenario/date date :scenario/price price}]
    (scenario/route-selection env selection)
    selection))

(defmethod ig/init-key ::mutations [_ _]
  [evaluate])

