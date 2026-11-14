(ns app.acl-test
  (:require [app.acl :as acl]
            [app.mutations.scenario :as scenario]
            [app.resolvers.city :as city]
            [app.resolvers.route :as route]
            [app.views.fields]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [datahike.api :as d]
            [eacl.core :as eacl]
            [integrant.core :as ig]
            [scriptorium.datahike :as sd]
            [scriptorium.datahike.conn]
            [scriptorium.datahike.pathom.mutations :as mutations]
            [scriptorium.datahike.pathom.resolvers :as stored]
            [scriptorium.eacl :as se]
            [scriptorium.eacl.pathom :as acl.pathom]
            [scriptorium.pathom.env]
            [scriptorium.pathom.resolvers :as views]
            [scriptorium.registry]))

(def ^:private user-id "b6d3bdf5-61f2-4d26-8a0e-8f1dc222c032")
(def ^:private allowed-forecast #uuid "ac43e492-96b1-4451-b9ab-02ddcd7d9c56")
(def ^:private forbidden-forecast #uuid "c368c9f9-6878-4fa1-b398-2f65bad001f6")
(def ^:private viewer-relations
  [{:relation :viewer :subject-type :user :resource-type :route}
   {:relation :viewer :subject-type :user :resource-type :city}])

(defn- forbidden? [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo error
         (boolean (some #(= :authz/forbidden (:type (ex-data %)))
                        (take-while some? (iterate ex-cause error)))))))

(defn- with-env [f]
  (let [registry (ig/init-key
                  :scriptorium/registry
                  {:entities (into {} (for [entity ["user" "city" "route" "forecast" "scenario"]]
                                        [(keyword "app.entities" entity)
                                         (edn/read-string (slurp (io/resource (str "entities/" entity ".edn"))))]))})
        config {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        conn (ig/init-key :scriptorium.datahike/conn (assoc config :registry registry))]
    (try
      (let [client (ig/init-key :scriptorium.eacl/client
                                {:registry registry :conn conn :extra-relations viewer-relations})
            transaction (sd/transaction {:conn conn :eacl-client client})
            generated (views/resolvers {:registry registry})]
        (transaction [{:city/id "MOW" :city/name "Москва"}
                      {:city/id "AER" :city/name "Сочи"}
                      {:city/id "LED" :city/name "Санкт-Петербург"}
                      {:city/id "KZN" :city/name "Казань"}])
        (transaction [{:route/id "MOW-AER" :route/name "Allowed"
                       :route/origin-city [:city/id "MOW"] :route/destination-city [:city/id "AER"]
                       :route/base-price 100.0 :route/price-sensitivity 1.0
                       :route/forecasts [{:forecast/id allowed-forecast :forecast/date "2026-12-01"
                                          :forecast/seats 5 :forecast/demand-q50 10.0}]}
                      {:route/id "MOW-LED" :route/name "Forbidden"
                       :route/origin-city [:city/id "MOW"] :route/destination-city [:city/id "LED"]
                       :route/forecasts [{:forecast/id forbidden-forecast :forecast/date "2026-12-02"
                                          :forecast/seats 99 :forecast/demand-q50 99.0}]}])
        (transaction [{:user/id user-id :user/email "restricted@example.test"}])
        (acl/grant-view! {:app.acl/client client} {:user/id user-id}
                         [{:city/id "KZN"} {:route/id "MOW-AER"}])
        (let [env (ig/init-key
                   :scriptorium.pathom.env/env
                   {:env {:app.acl/client client
                          :scriptorium.datahike/conn conn
                          :scriptorium.datahike/env->db (fn [_] (d/db conn))
                          :scriptorium.datahike/transaction transaction
                          :scriptorium.eacl/subject (se/resource registry {:user/id user-id})
                          :com.wsscode.pathom3.error/lenient-mode? false}
                    :plugins [(acl.pathom/plugin {:client client})]
                    :resolvers [generated (stored/resolvers {:registry registry})
                                (mutations/mutations {:registry registry})
                                scenario/evaluate route/origin-cities route/destination-cities route/forecast-dates]})]
          (f env)))
      (finally
        (ig/halt-key! :scriptorium.datahike/conn conn)
        (d/delete-database config)))))

(deftest authenticated-options-follow-explicit-viewer-grants
  (with-env
    (fn [env]
      (testing "unauthenticated root operations and mutation fail"
        (let [anonymous (dissoc env :scriptorium.eacl/subject)]
          (doseq [query [[{:route/origin-cities [:city/id]}]
                         [{:route/destination-cities [:city/id]}]
                         [{:route/forecast-dates [:forecast/date]}]
                         [(list 'app.mutations.scenario/evaluate
                                {:from "MOW" :to "AER" :date "2026-12-01" :price 100})]]]
            (is (forbidden? #(p.eql/process anonymous {} query))))))
      (testing "root options filter before protected child resolution"
        (is (= {:route/origin-cities [{:city/id "MOW"}]
                :route/destination-cities [{:city/id "AER"}]}
               (p.eql/process env {} [{:route/origin-cities [:city/id]}
                                      {:route/destination-cities [:city/id]}])))
        (acl/grant-view! env {:user/id user-id} [{:city/id "LED"}])
        (is (= {:route/origin-cities [{:city/id "MOW" :city/name "Москва"}]
                :route/destination-cities [{:city/id "AER" :city/name "Сочи"}]}
               (p.eql/process env {}
                              [{(list :route/origin-cities {:to "AER"}) [:city/id :city/name]}
                               {(list :route/destination-cities {:from "MOW"}) [:city/id :city/name]}])))
        (is (= {:route/origin-cities [] :route/destination-cities []}
               (p.eql/process env {}
                              [{(list :route/origin-cities {:to "LED"}) [:city/id]}
                               {(list :route/destination-cities {:from "AER"}) [:city/id]}])))
        (is (= {:route/forecast-dates [{:forecast/date "2026-12-01"}]}
               (p.eql/process env {} [{(list :route/forecast-dates {:from "MOW" :to "AER"})
                                       [:forecast/date]}])))
        (is (forbidden? #(p.eql/process env {} [{(list :route/forecast-dates {:from "MOW" :to "LED"})
                                                 [:forecast/date]}]))))
      (testing "generated pickers retain identity values and resolve their own labels"
        (let [fields (p.eql/process env {:scenario/id "current"} [:scenario/>from :scenario/>to])
              choices (fn [field]
                        (into #{}
                              (keep (fn [node]
                                      (when (and (vector? node) (= :option (first node))
                                                 (seq (:value (second node))))
                                        [(:value (second node)) (nth node 2)])))
                              (tree-seq coll? seq field)))]
          (is (= #{["MOW" "Москва"]} (choices (:scenario/>from fields))))
          (is (= #{["AER" "Сочи"]} (choices (:scenario/>to fields))))))
      (testing "shared cities expose only granted route edges"
        (is (= {:city/outgoing-routes [{:route/id "MOW-AER" :route/name "Allowed"}]}
               (p.eql/process env {:city/id "MOW"}
                              [{:city/outgoing-routes [:route/id :route/name]}])))
        (is (= {:city/name "Казань"}
               (p.eql/process env {:city/id "KZN"} [:city/name])))))))

(deftest forbidden-identities-cannot-be-read-or-forged-into-a-scenario
  (with-env
    (fn [env]
      (doseq [[entity query] [[{:route/id "MOW-LED"} [:route/name]]
                              [{:forecast/id forbidden-forecast} [:forecast/seats]]]]
        (is (forbidden? #(p.eql/process env entity query))))
      (is (forbidden? #(p.eql/process env
                                      {:scenario/id "current" :scenario/date "2026-12-01"
                                       :scenario/route {:route/id "MOW-LED"}}
                                      [{:scenario/forecast [:forecast/id]}])))
      (doseq [query [[:scenario/estimate] [:scenario/id]]]
        (is (forbidden? #(p.eql/process env {}
                                        [{(list 'app.mutations.scenario/evaluate
                                                {:from "MOW" :to "LED"
                                                 :date "2026-12-02" :price 100})
                                          query}])))))))

(deftest generic-transactions-cannot-self-grant-or-edit-account-fields
  (with-env
    (fn [env]
      (doseq [changes [{:user/routes [{:route/id "MOW-LED"}]}
                       {:user/cities [{:city/id "LED"}]}
                       {:user/email "changed@example.test"}
                       {:user/password-hash "forged"}]]
        (let [result (p.eql/process env {}
                                    [{(list 'scriptorium.datahike/transact (assoc changes :user/id user-id))
                                      [:tx/status]}])]
          (is (not= :ok (get-in result ['scriptorium.datahike/transact :tx/status])))))
      (is (false? (acl/can-view? env {:route/id "MOW-LED"})))
      (is (false? (acl/can-view? env {:city/id "LED"})))
      (is (= {:user/email "restricted@example.test"}
             (p.eql/process env {:user/id user-id} [:user/email]))))))

(deftest registration-grants-survive-writes-and-client-reinitialization
  (with-env
    (fn [env]
      (let [component (:app.acl/client env)
            conn (:scriptorium.datahike/conn env)
            registry (:registry component)
            tx (:scriptorium.datahike/transaction env)
            subject (:scriptorium.eacl/subject env)
            route (se/resource registry {:route/id "MOW-AER"})
            reload-client #(ig/init-key :scriptorium.eacl/client
                                        {:registry registry :conn conn
                                         :extra-relations viewer-relations})]
        (tx [{:city/id "KZN" :city/name "Казань обновлённая"}])
        (let [component (reload-client)
              refreshed (assoc env :app.acl/client component)]
          (is (acl/can-view? refreshed {:route/id "MOW-AER"}))
          (is (acl/can-view? refreshed {:city/id "KZN"}))
          (is (acl/can-view? refreshed {:forecast/id allowed-forecast}))
          (doseq [resource [route (se/resource registry {:city/id "KZN"})
                            (se/resource registry {:forecast/id allowed-forecast})]
                  permission [:transact :delete]]
            (is (false? (eacl/can? (:client component) subject permission resource))))
          (eacl/write-relationships!
           (:client component)
           {:updates [{:operation :delete
                       :relationship (eacl/->Relationship subject :viewer route)}]})
          (tx [{:city/id "KZN" :city/name "Казань"}])
          (let [revoked (assoc env :app.acl/client (reload-client))]
            (is (false? (acl/can-view? revoked {:route/id "MOW-AER"}))))
          (is (false? (acl/can-view? (assoc env :app.acl/client (reload-client))
                                     {:forecast/id allowed-forecast}))))))))

(deftest registration-grant-inputs-fail-before-any-write
  (with-env
    (fn [env]
      (is (thrown? clojure.lang.ExceptionInfo
                   (acl/grant-view! env {:city/id "MOW"} [{:route/id "MOW-LED"}])))
      (is (thrown? clojure.lang.ExceptionInfo
                   (acl/grant-view! env {:user/id user-id}
                                    [{:route/id "MOW-LED"} {:forecast/id forbidden-forecast}])))
      (is (false? (acl/can-view? env {:route/id "MOW-LED"}))))))

(deftest authorized-route-queries-cross-page-boundaries-and-handle-no-grants
  (with-env
    (fn [env]
      (let [tx (:scriptorium.datahike/transaction env)
            routes (mapv #(hash-map :route/id (str "extra-" %)
                                    :route/origin-city [:city/id "MOW"]
                                    :route/destination-city [:city/id "AER"])
                         (range 101))
            expected (conj (set (map :route/id routes)) "MOW-AER")]
        (tx routes)
        (acl/grant-view! env {:user/id user-id} (mapv #(select-keys % [:route/id]) routes))
        (is (= expected (set (map :route/id (city/outgoing-routes env {:city/id "MOW"})))))
        (is (= {:route/origin-cities [{:city/id "MOW"}]
                :route/destination-cities [{:city/id "AER"}]}
               (p.eql/process env {} [{:route/origin-cities [:city/id]}
                                      {:route/destination-cities [:city/id]}])))
        (tx [{:user/id "no-grants" :user/email "none@example.test"}])
        (let [ungranted (assoc env :scriptorium.eacl/subject
                               (se/resource (:registry (:app.acl/client env))
                                            {:user/id "no-grants"}))]
          (is (= [] (city/outgoing-routes ungranted {:city/id "MOW"})))
          (is (= {:route/origin-cities [] :route/destination-cities []}
                 (p.eql/process ungranted {} [{:route/origin-cities [:city/id]}
                                              {:route/destination-cities [:city/id]}]))))))))
