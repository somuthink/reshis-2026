(ns app.mutations.user-test
  (:require [app.acl :as acl]
            [app.mutations.user :as mutations]
            [app.password :as password]
            [app.resolvers.user :as user]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [scriptorium.reitit.eql :as eql]))

(def ^:private valid-params
  {:email " Demo@Example.Test " :password "long-enough-password" :cities ["B" "A" "A"]})

(deftest registration-route-access-requires-both-endpoints
  (with-redefs [d/q (fn [_ _] #{["AB" "A" "B"] ["AC" "A" "C"] ["BC" "B" "C"]})]
    (is (= [{:route/id "AB"}]
           (user/registration-routes {:scriptorium.datahike/env->db (constantly ::db)} ["A" "B"])))
    (is (= [] (user/registration-routes {:scriptorium.datahike/env->db (constantly ::db)} [])))))

(deftest invalid-registration-never-writes
  (with-redefs [user/cities (fn [_] [{:city/id "A"}])]
    (doseq [params [(assoc valid-params :email [])
                    (assoc valid-params :password nil)
                    (assoc valid-params :cities "A")
                    (assoc valid-params :cities ["unknown"])
                    (assoc valid-params :cities [1])]]
      (let [result (mutations/register {:scriptorium.datahike/transaction
                                        (fn [_] (throw (AssertionError. "Unexpected write")))} params)]
        (is (= #{:user/authenticated? :user/errors} (set (keys result))))
        (is (false? (:user/authenticated? result)))
        (is (vector? (:user/errors result)))
        (is (seq (:user/errors result)))))))

(deftest duplicate-email-races-are-the-only-write-errors-translated
  (with-redefs [user/cities (fn [_] [{:city/id "A"} {:city/id "B"}])
                user/by-email (fn [_ _] nil)
                user/registration-routes (fn [_ _] [])
                password/password-hash (constantly "private-hash")
                acl/grant-view! (fn [& _] (throw (AssertionError. "Unexpected grant")))
                eql/augment-response (fn [& _] (throw (AssertionError. "Unexpected session")))]
    (let [duplicate (ex-info "Duplicate" {:error :transact/unique :attribute :user/email})]
      (is (= {:user/authenticated? false
              :user/errors ["Этот адрес электронной почты уже зарегистрирован."]}
             (mutations/register
              {:scriptorium.datahike/transaction (fn [_] (throw (ex-info "Wrapped" {} duplicate)))}
              valid-params))))
    (doseq [error [(ex-info "Storage failed" {})
                   (ex-info "Another unique attribute" {:error :transact/unique :attribute :user/id})]]
      (is (identical? error
                      (try
                        (mutations/register {:scriptorium.datahike/transaction (fn [_] (throw error))} valid-params)
                        (catch Exception caught caught)))))))

(deftest grant-failure-never-emits-a-session-or-pretends-to-be-a-duplicate
  (let [error (ex-info "Grant failed" {:type :authz/forbidden})]
    (with-redefs [user/cities (fn [_] [{:city/id "A"} {:city/id "B"}])
                  user/by-email (fn [_ _] nil)
                  user/registration-routes (fn [_ _] [])
                  password/password-hash (constantly "private-hash")
                  acl/grant-view! (fn [& _] (throw error))
                  eql/augment-response (fn [& _] (throw (AssertionError. "Unexpected session")))]
      (is (identical? error
                      (try
                        (mutations/register {:scriptorium.datahike/transaction (constantly nil)} valid-params)
                        (catch Exception caught caught)))))))

(deftest logout-returns-only-transient-data-and-clears-the-session
  (let [result (mutations/logout {} {})
        transforms (::eql/response-transforms (meta result))]
    (is (= {:user/authenticated? false :user/errors []} result))
    (is (= 1 (count transforms)))
    (is (= {:status 200 :session nil}
           ((first transforms) {:status 200 :session {:user/id "one"}})))))

