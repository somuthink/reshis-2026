(ns app.resolvers.scenario-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [datahike.api :as d]
            [integrant.core :as ig]
            [scriptorium.datahike :as sd]
            [scriptorium.eacl :as acl]
            [scriptorium.eacl.pathom :as acl.pathom]
            [scriptorium.registry]
            [scriptorium.datahike.conn]
            [scriptorium.pathom.env]
            [scriptorium.pathom.resolvers :as views]
            [scriptorium.datahike.pathom.resolvers :as stored]
            [app.acl :as app-acl]
            [app.mutations.scenario :as scenario]
            [app.resolvers.route :as route]
            [app.resolvers.scenario :as resolvers]))

(deftest estimate-sales-applies-price-sensitivity-and-seat-cap
  (let [estimate #(resolvers/estimate-sales 775.0 900 9000.0 0.141 %)]
    (is (= {:demand 634.0 :sales 634.0} (estimate 10000.0)))
    (let [{:keys [demand sales]} (estimate 0.0)]
      (is (< (Math/abs (- 2044.0 demand)) 1.0e-9))
      (is (= 900.0 sales)))))

(deftest estimate-sales-clamps-only-sales
  (let [estimate #(resolvers/estimate-sales 10.0 5 100.0 1.0 %)]
    (is (= {:demand -1.0 :sales 0.0} (estimate 111.0)))
    (is (= {:demand 0.0 :sales 0.0} (estimate 110.0)))
    (is (= {:demand 5.0 :sales 5.0} (estimate 105.0)))
    (is (= {:demand 10.0 :sales 5.0} (estimate 100.0)))
    (is (= {:demand 10.0 :sales 0.0}
           (resolvers/estimate-sales 10.0 0 100.0 1.0 100.0)))))

(deftest distribution-adjusts-all-quantiles-without-sales-clamping
  (let [route {:route/base-price 100.0 :route/price-sensitivity 2.0}
        forecast (zipmap [:forecast/demand-q10 :forecast/demand-q20 :forecast/demand-q30
                          :forecast/demand-q40 :forecast/demand-q50 :forecast/demand-q60
                          :forecast/demand-q70 :forecast/demand-q80 :forecast/demand-q90]
                         [0.0 5.0 10.0 15.0 20.0 25.0 30.0 35.0 40.0])
        input {:scenario/route route :scenario/forecast forecast :scenario/price 105.0}
        points (resolvers/distribution input)
        summary (resolvers/estimate-sales 20.0 3 100.0 2.0 105.0)]
    (is (= [0.1 0.2 0.3 0.4 0.5 0.6 0.7 0.8 0.9] (mapv :quantile points)))
    (is (= [-10.0 -5.0 0.0 5.0 10.0 15.0 20.0 25.0 30.0] (mapv :demand points)))
    (is (= (:demand summary) (:demand (nth points 4)) 10.0))
    (is (= 3.0 (:sales summary)))
    (is (= [20.0 25.0 30.0 35.0 40.0 45.0 50.0 55.0 60.0]
           (mapv :demand (resolvers/distribution (assoc input :scenario/price 90.0)))))
    (is (nil? (resolvers/distribution (assoc input :scenario/forecast nil))))
    (is (nil? (resolvers/distribution (assoc input :scenario/route nil))))))

(deftest forecast-selection-skips-database-access-without-a-route
  (is (nil?
       (resolvers/forecast-selection
        {:scriptorium.datahike/env->db
         (fn [_] (throw (ex-info "Unexpected database access" {})))}
        {:scenario/date "2026-12-01" :scenario/route nil}))))

(deftest slider-route-waits-for-complete-selection
  (let [env (ig/init-key :scriptorium.pathom.env/env
                         {:env {:scriptorium.datahike/env->db
                                (fn [_] (throw (ex-info "Unexpected database access" {})))}
                          :resolvers [route/forecast-dates]})]
    (doseq [selection [{} {:from "MOW"} {:to "AER"}]]
      (is (= {:route/selected nil}
             (p.eql/process env {}
                            [{(list :route/selected selection) [:route/id]}]))))))

(deftest scenario-selects-only-matching-routes-and-computes-through-the-registry
  (let [registry (ig/init-key
                  :scriptorium/registry
                  {:entities (into {} (for [entity ["user" "city" "route" "forecast" "scenario"]]
                                        [(keyword "app.entities" entity)
                                         (edn/read-string (slurp (io/resource (str "entities/" entity ".edn"))))]))})
        forecast-id (random-uuid)
        config {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        conn (ig/init-key :scriptorium.datahike/conn (assoc config :registry registry))
        client (ig/init-key :scriptorium.eacl/client
                            {:registry registry :conn conn
                             :extra-relations [{:relation :viewer :subject-type :user :resource-type :route}
                                               {:relation :viewer :subject-type :user :resource-type :city}]})
        transaction (sd/transaction {:conn conn :eacl-client client})]
    (try
      (transaction [{:city/id "MOW" :city/name "Москва"}
                    {:city/id "AER" :city/name "Сочи"}
                    {:city/id "LED" :city/name "Санкт-Петербург"}
                    {:city/id "KZN" :city/name "Казань"}])
      (transaction
       [{:route/id "MOW-AER" :route/name "MOW → AER"
         :route/origin-city [:city/id "MOW"] :route/destination-city [:city/id "AER"]
         :route/base-price 9000.0 :route/price-sensitivity 0.141
         :route/price-min 5400.0 :route/price-max 18000.0
         :route/forecasts [{:forecast/id forecast-id :forecast/date "2026-12-01"
                            :forecast/seats 900 :forecast/demand-q50 775.0}
                           {:forecast/id (random-uuid) :forecast/date "2026-12-03"}]}
        {:route/id "AER-MOW" :route/name "AER → MOW"
         :route/origin-city [:city/id "AER"] :route/destination-city [:city/id "MOW"]
         :route/base-price 9000.0 :route/price-sensitivity 0.141
         :route/forecasts [{:forecast/id (random-uuid) :forecast/date "2026-12-01"
                            :forecast/seats 100 :forecast/demand-q50 300.0}]}
                   ;; Missing calculation data makes accidental loading of unrelated routes fail.
        {:route/id "MOW-LED" :route/origin-city [:city/id "MOW"]
         :route/destination-city [:city/id "LED"]}])
      (transaction [{:user/id "scenario-test" :user/email "scenario@example.test"}])
      (app-acl/grant-view!
       {:app.acl/client client} {:user/id "scenario-test"}
       [{:city/id "MOW"} {:city/id "AER"} {:city/id "LED"} {:city/id "KZN"}
        {:route/id "MOW-AER"} {:route/id "AER-MOW"} {:route/id "MOW-LED"}])
      (let [env (ig/init-key
                 :scriptorium.pathom.env/env
                 {:env {:scriptorium.datahike/env->db (fn [_] (d/db conn))
                        :app.acl/client client
                        :scriptorium.eacl/subject (acl/resource registry {:user/id "scenario-test"})
                        :com.wsscode.pathom3.error/lenient-mode? false}
                  :plugins [(acl.pathom/plugin {:client client})]
                  :resolvers [scenario/evaluate scenario/set-target-sales route/origin-cities route/destination-cities route/forecast-dates
                              (views/resolvers {:registry registry})
                              (stored/resolvers {:registry registry})]})
            cities (fn [attribute params]
                     (set (get (p.eql/process env {}
                                              [{(if (nil? params) attribute (list attribute params))
                                                [:city/id :city/name]}])
                               attribute)))
            dates (fn [parameters]
                    (mapv :forecast/date
                          (:route/forecast-dates
                           (p.eql/process env {}
                                          [{(list :route/forecast-dates parameters)
                                            [:forecast/date]}]))))
            scenario (fn [params query]
                       (get (p.eql/process env {} [{(list 'app.mutations.scenario/evaluate params) query}])
                            'app.mutations.scenario/evaluate))
            estimate (fn [params] (:scenario/estimate (scenario params [:scenario/estimate])))
            params {:from "MOW" :to "AER" :date "2026-12-01" :price 10000.0}
            seed {:scenario/id "current"
                  :scenario/from {:city/id "MOW"} :scenario/to {:city/id "AER"}
                  :scenario/date (java.time.LocalDate/parse "2026-12-01") :scenario/price 10000.0}]
        (testing "date options are route-specific and do not fill gaps in forecast coverage"
          (is (= ["2026-12-01" "2026-12-03"] (dates {:from "MOW" :to "AER"})))
          (is (= ["2026-12-01"] (dates {:from "AER" :to "MOW"})))
          (doseq [selection [{} {:from "MOW"} {:to "AER"} {:from "" :to "AER"}
                             {:from "AER" :to "LED"} {:from "MOW" :to "LED"}]]
            (is (= [] (dates selection)))))
        (testing "date option params reject wrong types and unknown keys"
          (doseq [invalid [{:from nil} {:from []} {:to 123} {:unknown "MOW"}]]
            (is (thrown? clojure.lang.ExceptionInfo (dates invalid)))))
        (testing "city relations follow route direction and include empty collections"
          (is (= {:city/outgoing-routes #{{:route/id "MOW-AER"} {:route/id "MOW-LED"}}
                  :city/incoming-routes #{{:route/id "AER-MOW"}}}
                 (update-vals
                  (p.eql/process env {:city/id "MOW"}
                                 [{:city/outgoing-routes [:route/id]}
                                  {:city/incoming-routes [:route/id]}])
                  set)))
          (is (= {:city/outgoing-routes [] :city/incoming-routes []}
                 (p.eql/process env {:city/id "KZN"}
                                [{:city/outgoing-routes [:route/id]}
                                 {:city/incoming-routes [:route/id]}]))))
        (testing "route and forecast references derive from supplied scenario data"
          (is (= {:scenario/route {:route/id "MOW-AER" :route/name "MOW → AER"}
                  :scenario/forecast {:forecast/id forecast-id}
                  :scenario/estimate {:name "MOW → AER" :date "2026-12-01" :price 10000.0
                                      :median-demand 775.0 :seats 900 :demand 634.0 :sales 634.0}}
                 (p.eql/process env seed
                                [{:scenario/route [:route/id :route/name]}
                                 {:scenario/forecast [:forecast/id]}
                                 :scenario/estimate])))
          (is (= {:scenario/forecast nil}
                 (p.eql/process env (assoc seed :scenario/date (java.time.LocalDate/parse "2026-12-02"))
                                [{:scenario/forecast [:forecast/id]}]))))
        (testing "global directional variants contain full cities with matching edges only"
          (let [mow {:city/id "MOW" :city/name "Москва"}
                aer {:city/id "AER" :city/name "Сочи"}
                led {:city/id "LED" :city/name "Санкт-Петербург"}]
            (is (= #{mow aer} (cities :route/origin-cities nil)))
            (is (= #{mow aer led} (cities :route/destination-cities nil)))
            (is (= #{mow} (cities :route/origin-cities {:to "AER"})))
            (is (= #{aer} (cities :route/origin-cities {:to "MOW"})))
            (is (= #{aer led} (cities :route/destination-cities {:from "MOW"})))
            (is (= #{mow} (cities :route/destination-cities {:from "AER"})))
            (is (= #{mow aer} (cities :route/origin-cities {:to ""})))
            (is (= #{mow aer led} (cities :route/destination-cities {:from ""})))
            (is (= #{mow aer} (cities :route/origin-cities {})))
            (is (= #{mow aer led} (cities :route/destination-cities {})))
            (is (= #{} (cities :route/origin-cities {:to "KZN"})))
            (is (= #{} (cities :route/destination-cities {:from "LED"})))))
        (testing "directional variants reject wrong types and unknown parameters"
          (doseq [[attribute parameter] [[:route/origin-cities :to] [:route/destination-cities :from]]
                  invalid [{parameter nil} {parameter 123} {parameter []}
                           {:unknown "MOW"}]]
            (is (thrown? clojure.lang.ExceptionInfo (cities attribute invalid)))))
        (testing "date label parameters preserve an accessible, constrained date input"
          (let [input (assoc seed :route/forecast-dates [{:forecast/date "2026-12-01"}])
                render #(get (p.eql/process env input [(list :scenario/>date %)]) :scenario/>date)
                nodes #(filter vector? (tree-seq coll? seq %))
                labeled (render {})
                hidden (render {:label? false})
                control (second (first (filter #(= :input (first %)) (nodes hidden))))]
            (is (some #(= :label (first %)) (nodes labeled)))
            (is (not-any? #(= :label (first %)) (nodes hidden)))
            (is (= "2026-12-01" (:value control) (:min control) (:max control)))
            (is (and (:required control) (not (:disabled control)) (:aria-label control)))
            (is (thrown? clojure.lang.ExceptionInfo (render {:label? []})))))
        (testing "the virtual scenario exposes singular references and fully joined data"
          (is (= {:scenario/id "current"
                  :scenario/from {:city/id "MOW" :city/name "Москва"}
                  :scenario/to {:city/id "AER" :city/name "Сочи"}
                  :scenario/date (java.time.LocalDate/parse "2026-12-01") :scenario/price 10000.0
                  :scenario/route {:route/id "MOW-AER" :route/name "MOW → AER"
                                   :route/base-price 9000.0 :route/price-sensitivity 0.141}
                  :scenario/forecast {:forecast/id forecast-id :forecast/date "2026-12-01"
                                      :forecast/seats 900 :forecast/demand-q50 775.0}
                  :scenario/estimate {:name "MOW → AER" :date "2026-12-01" :price 10000.0
                                      :median-demand 775.0 :seats 900 :demand 634.0 :sales 634.0}}
                 (scenario params
                           [:scenario/id :scenario/date :scenario/price
                            {:scenario/from [:city/id :city/name]}
                            {:scenario/to [:city/id :city/name]}
                            {:scenario/route [:route/id :route/name :route/base-price
                                              :route/price-sensitivity]}
                            {:scenario/forecast [:forecast/id :forecast/date :forecast/seats
                                                 :forecast/demand-q50]}
                            :scenario/estimate]))))
        (testing "directional route selection does not resolve unrelated routes"
          (is (= {:name "MOW → AER" :date "2026-12-01" :price 10000.0
                  :median-demand 775.0 :seats 900 :demand 634.0 :sales 634.0}
                 (estimate params)))
          (is (= {:name "AER → MOW" :date "2026-12-01" :price 10000.0
                  :median-demand 300.0 :seats 100 :demand 159.0 :sales 100.0}
                 (estimate (assoc params :from "AER" :to "MOW")))))
        (testing "missing dates and routes are reported without persistence lookup errors"
          (is (= {:error "No forecast exists for 2026-12-02 on route MOW → AER."}
                 (estimate (assoc params :date "2026-12-02"))))
          (is (= {:error "No route exists from AER to LED."}
                 (estimate (assoc params :from "AER" :to "LED")))))
        (testing "target mutation returns the new price and matching forecast in one result"
          (let [run #(get (p.eql/process env {}
                                         [{(list 'app.mutations.scenario/set-target-sales (assoc params :target %))
                                           [:scenario/price :scenario/estimate {:route/selected [:route/id]}]}])
                          'app.mutations.scenario/set-target-sales)
                result (run 700)
                failed (run 901)]
            (is (< (Math/abs (- 700.0 (get-in result [:scenario/estimate :demand]))) 1e-8))
            (is (= (:scenario/price result) (get-in result [:scenario/estimate :price])))
            (is (= {:route/id "MOW-AER"} (:route/selected result)))
            (is (= 10000.0 (:scenario/price failed)))
            (is (contains? (:scenario/estimate failed) :error))))
        (testing "whole-number prices work and invalid parameters are rejected"
          (is (= 634.0 (:sales (estimate (assoc params :price 10000)))))
          (doseq [invalid [(dissoc params :from) (assoc params :to "")
                           (assoc params :date "2026-02-30")
                           (assoc params :price -1) (assoc params :price Double/NaN)]]
            (is (thrown? clojure.lang.ExceptionInfo (estimate invalid)))))
        (testing "duplicate forecasts for a date are never selected arbitrarily"
          (transaction [{:route/id "MOW-AER"
                         :route/forecasts [{:forecast/id (random-uuid)
                                            :forecast/date "2026-12-01"
                                            :forecast/seats 1 :forecast/demand-q50 1.0}]}])
          (is (thrown? clojure.lang.ExceptionInfo (estimate params))))
        (testing "different route identities with the same endpoints are ambiguous"
          (transaction [{:route/id "MOW-AER-SECOND"
                         :route/origin-city [:city/id "MOW"]
                         :route/destination-city [:city/id "AER"]}])
          (is (thrown? clojure.lang.ExceptionInfo (estimate params)))
          (is (thrown? clojure.lang.ExceptionInfo (dates {:from "MOW" :to "AER"})))))
      (finally
        (ig/halt-key! :scriptorium.datahike/conn conn)
        (d/delete-database config)))))
