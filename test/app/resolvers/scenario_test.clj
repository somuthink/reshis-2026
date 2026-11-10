(ns app.resolvers.scenario-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [datahike.api :as d]
            [integrant.core :as ig]
            [scriptorium.registry]
            [scriptorium.datahike.conn]
            [scriptorium.pathom.env]
            [scriptorium.pathom.resolvers :as views]
            [scriptorium.datahike.pathom.resolvers :as stored]
            [app.scenario :as scenario]
            [app.resolvers.route :as route]
            [app.resolvers.scenario :as resolvers]))

(deftest estimate-sales-applies-price-sensitivity-and-seat-cap
  (let [estimate #(scenario/estimate-sales 775.0 900 9000.0 0.141 %)]
    (is (= {:demand 634.0 :sales 634.0} (estimate 10000.0)))
    (let [{:keys [demand sales]} (estimate 0.0)]
      (is (< (Math/abs (- 2044.0 demand)) 1.0e-9))
      (is (= 900.0 sales)))))

(deftest estimate-sales-clamps-only-sales
  (let [estimate #(scenario/estimate-sales 10.0 5 100.0 1.0 %)]
    (is (= {:demand -1.0 :sales 0.0} (estimate 111.0)))
    (is (= {:demand 0.0 :sales 0.0} (estimate 110.0)))
    (is (= {:demand 5.0 :sales 5.0} (estimate 105.0)))
    (is (= {:demand 10.0 :sales 5.0} (estimate 100.0)))
    (is (= {:demand 10.0 :sales 0.0}
           (scenario/estimate-sales 10.0 0 100.0 1.0 100.0)))))

(deftest forecast-selection-skips-database-access-without-a-route
  (is (nil?
       (resolvers/forecast-selection
        {:scriptorium.datahike/env->db
         (fn [_] (throw (ex-info "Unexpected database access" {})))}
        {:scenario/date "2026-12-01" :scenario/route nil}))))

(deftest scenario-selects-only-matching-routes-and-computes-through-the-registry
  (let [registry (ig/init-key
                  :scriptorium/registry
                  {:entities (into {} (for [entity ["city" "route" "forecast" "scenario"]]
                                        [(keyword "app.entities" entity)
                                         (edn/read-string (slurp (io/resource (str "entities/" entity ".edn"))))]))})
        forecast-id (random-uuid)
        config {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        conn (ig/init-key :scriptorium.datahike/conn (assoc config :registry registry))]
    (try
      (d/transact conn [{:city/id "MOW" :city/name "Москва"}
                        {:city/id "AER" :city/name "Сочи"}
                        {:city/id "LED" :city/name "Санкт-Петербург"}
                        {:city/id "KZN" :city/name "Казань"}])
      (d/transact conn
                  [{:route/id "MOW-AER" :route/name "MOW → AER"
                    :route/origin-city [:city/id "MOW"] :route/destination-city [:city/id "AER"]
                    :route/base-price 9000.0 :route/price-sensitivity 0.141
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
      (let [env (ig/init-key
                 :scriptorium.pathom.env/env
                 {:env {:scriptorium.datahike/env->db (fn [_] (d/db conn))
                        :com.wsscode.pathom3.error/lenient-mode? false}
                  :resolvers [scenario/evaluate route/origin-cities route/destination-cities
                              (views/resolvers {:registry registry})
                              (stored/resolvers {:registry registry})]})
            cities (fn [attribute params]
                     (set (get (p.eql/process env {}
                                              [{(if (nil? params) attribute (list attribute params))
                                                [:city/id :city/name]}])
                               attribute)))
            scenario (fn [params query]
                       (get (p.eql/process env {} [{(list 'app.scenario/evaluate params) query}])
                            'app.scenario/evaluate))
            estimate (fn [params] (:scenario/estimate (scenario params [:scenario/estimate])))
            params {:from "MOW" :to "AER" :date "2026-12-01" :price 10000.0}
            seed {:scenario/id "current"
                  :scenario/from {:city/id "MOW"} :scenario/to {:city/id "AER"}
                  :scenario/date "2026-12-01" :scenario/price 10000.0}]
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
                 (p.eql/process env (assoc seed :scenario/date "2026-12-02")
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
        (testing "the virtual scenario exposes singular references and fully joined data"
          (is (= {:scenario/id "current"
                  :scenario/from {:city/id "MOW" :city/name "Москва"}
                  :scenario/to {:city/id "AER" :city/name "Сочи"}
                  :scenario/date "2026-12-01" :scenario/price 10000.0
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
        (testing "whole-number prices work and invalid parameters are rejected"
          (is (= 634.0 (:sales (estimate (assoc params :price 10000)))))
          (doseq [invalid [(dissoc params :from) (assoc params :to "")
                           (assoc params :price -1) (assoc params :price Double/NaN)]]
            (is (thrown? clojure.lang.ExceptionInfo (estimate invalid)))))
        (testing "duplicate forecasts for a date are never selected arbitrarily"
          (d/transact conn [{:route/id "MOW-AER"
                             :route/forecasts [{:forecast/id (random-uuid)
                                                :forecast/date "2026-12-01"
                                                :forecast/seats 1 :forecast/demand-q50 1.0}]}])
          (is (thrown? clojure.lang.ExceptionInfo (estimate params))))
        (testing "different route identities with the same endpoints are ambiguous"
          (d/transact conn [{:route/id "MOW-AER-SECOND"
                             :route/origin-city [:city/id "MOW"]
                             :route/destination-city [:city/id "AER"]}])
          (is (thrown? clojure.lang.ExceptionInfo (estimate params)))))
      (finally
        (ig/halt-key! :scriptorium.datahike/conn conn)
        (d/delete-database config)))))
