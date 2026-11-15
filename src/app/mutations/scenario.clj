(ns app.mutations.scenario
  (:require [app.acl :as acl]
            [app.resolvers.scenario :as scenario]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.interface.eql :as eql]
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

(pco/defmutation set-target-sales
  [env {:keys [target] :as parameters}]
  {::params/schema [:map {:closed true}
                    [:from [:string {:min 1}]] [:to [:string {:min 1}]]
                    [:date [:re "[0-9]{4}-[0-9]{2}-[0-9]{2}"]]
                    [:price [:or [:int {:min 0}] [:double {:min 0 :max 1.7976931348623157E308}]]]
                    [:target [:int {:min 1}]]]
   :eacl/permission :view
   :eacl/resource acl/subject-resource
   ::pco/output [:scenario/id {:scenario/from [:city/id]} {:scenario/to [:city/id]}
                 :scenario/date :scenario/price :scenario/estimate {:route/selected [:route/id]}]}
  (let [selection (evaluate env (dissoc parameters :target))
        {:scenario/keys [route target-price]}
        (eql/process env selection [{:scenario/route [:route/id]}
                                    (list :scenario/target-price {:target target})])
        updated (cond-> (assoc selection :route/selected route)
                  (:price target-price) (assoc :scenario/price (:price target-price)))]
    (assoc updated :scenario/estimate
           (if-let [error (:error target-price)]
             {:error error}
             (:scenario/estimate (eql/process env updated [:scenario/estimate]))))))

(defmethod ig/init-key ::mutations [_ _]
  [evaluate set-target-sales])

