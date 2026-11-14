(ns app.user-test
  (:require [app.acl :as acl]
            [app.mutations.user :as mutations]
            [app.password :as password]
            [app.reitit.middleware :as middleware]
            [app.reitit.handlers.html.user]
            [app.resolvers.user :as user]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [com.wsscode.pathom3.interface.eql :as p.eql]
            [datahike.api :as d]
            [integrant.core :as ig]
            [scriptorium.datahike :as sd]
            [scriptorium.datahike.conn]
            [scriptorium.datahike.pathom.resolvers :as db-resolvers]
            [scriptorium.eacl :as eacl]
            [scriptorium.eacl.pathom :as eacl.pathom]
            [scriptorium.pathom.env]
            [scriptorium.reitit.handlers.eql :as eql-handler]
            [scriptorium.reitit.eql :as eql]
            [scriptorium.pathom.resolvers :as views]
            [scriptorium.registry]))

(defn- with-system [f]
  (let [registry (ig/init-key :scriptorium/registry
                              {:entities (into {} (for [entity ["user" "city" "route" "forecast" "scenario"]]
                                                    [(keyword "app.entities" entity)
                                                     (edn/read-string (slurp (io/resource (str "entities/" entity ".edn"))))]))})
        config {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}
        conn (ig/init-key :scriptorium.datahike/conn (assoc config :registry registry))]
    (try
      (let [client (ig/init-key :scriptorium.eacl/client
                                {:registry registry :conn conn
                                 :extra-relations [{:relation :viewer :subject-type :user :resource-type :route}
                                                   {:relation :viewer :subject-type :user :resource-type :city}]})
            transaction (sd/transaction {:conn conn :eacl-client client})
            env (ig/init-key :scriptorium.pathom.env/env
                             {:env {:app.acl/client client :scriptorium.datahike/conn conn
                                    :scriptorium.datahike/env->db (fn [_] (d/db conn))
                                    :scriptorium.datahike/transaction transaction
                                    :com.wsscode.pathom3.error/lenient-mode? false}
                              :plugins [(eacl.pathom/plugin {:client client})]
                              :resolvers [(views/resolvers {:registry registry})
                                          (db-resolvers/resolvers {:registry registry})
                                          mutations/login mutations/register mutations/logout
                                          user/registration-cities]})
            eql (eql-handler/handler {:pathom-env env :registry registry})
            handler (ig/init-key :app.reitit.middleware/handler
                                 {:registry registry :pathom-env env
                                  :handler (fn [request]
                                             (if (= "/eql" (:uri request))
                                               (eql request)
                                               {:status 200 :body (:identity request)}))})]
        (transaction [{:city/id "A" :city/name "A"} {:city/id "B" :city/name "B"} {:city/id "C" :city/name "C"}])
        (transaction [{:route/id "AB" :route/origin-city [:city/id "A"] :route/destination-city [:city/id "B"]}
                      {:route/id "AC" :route/origin-city [:city/id "A"] :route/destination-city [:city/id "C"]}])
        (f env handler conn))
      (finally (d/release conn) (d/delete-database config)))))

(defn- request [handler method uri cookie query origin]
  (handler {:request-method method :uri uri :scheme :https
            :parameters {:body {:entity {} :query (if (string? query) query (pr-str query))}}
            :headers (cond-> {"host" "app.test"}
                       cookie (assoc "cookie" cookie)
                       origin (assoc "origin" origin))}))

(defn- cookie [response]
  (let [header (get-in response [:headers "Set-Cookie"])]
    (first (str/split (if (string? header) header (first header)) #";"))))

(defn- call [operation params]
  [{(list operation params) [:user/>auth-result]}])

(defn- forbidden? [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo error
         (boolean (some #(= :authz/forbidden (:type (ex-data %)))
                        (take-while some? (iterate ex-cause error)))))))

(deftest user-form-uses-temporary-inputs-and-authorized-persistent-data
  (with-system
    (fn [env _ conn]
      (let [render #(get (p.eql/process %1 %2 [%3]) %3)
            inputs #(filter (fn [node] (and (vector? node) (= :input (first node))))
                            (tree-seq coll? seq %))
            fields #(into {} (map (fn [[_ attrs]] [(:name attrs) attrs])) (inputs %))
            labels #(into #{} (keep (fn [node]
                                      (when (and (vector? node) (= :label (first node)))
                                        (nth node 2 nil))))
                          (tree-seq coll? seq %))
            draft {:user/id (str "temp-" (random-uuid)) :user/email ""}
            registration (render env draft :user/>register-form)
            login (render env draft :user/>login-form)
            password (get (fields registration) "user/password")
            secret "123456"
            registered (mutations/register env {:email "form@example.test" :password secret :cities ["A" "B"]})
            id (:user/id registered)
            scoped (assoc env :scriptorium.eacl/subject
                          (eacl/resource (:registry (:app.acl/client env)) {:user/id id}))
            account (render scoped {:user/id id} :user/>form)
            account-fields (fields account)]
        (is (contains? (labels registration) "Пароль"))
        (is (= {:type "password" :value "" :autocomplete "new-password" :minlength 6 :maxlength 256}
               (select-keys password [:type :value :autocomplete :minlength :maxlength])))
        (is (= "current-password" (:autocomplete (get (fields login) "user/password"))))
        (is (= 6 (:minlength (get (fields login) "user/password"))))
        (is (= #{"A" "B" "C"}
               (into #{} (comp (map second) (filter #(and (= "user/cities" (:name %)) (:checked %)))
                               (map :value)) (inputs registration))))
        (is (= "" (get-in (p.eql/process env (assoc draft :user/password secret) [:user/>password])
                          [:user/>password 3 1 :value])))
        (is (= (str "user-form-" id) (get-in account [1 :id])))
        (is (= "form@example.test" (:value (get account-fields "user/email"))))
        (is (not (contains? account-fields "user/password")))
        (is (not (contains? account-fields "user/cities")))
        (doseq [view [:user/>form :user/>login-form :user/>register-form]]
          (is (forbidden? #(render env {:user/id id} view)))
          (is (forbidden? #(render (assoc scoped :scriptorium.eacl/subject
                                          (eacl/resource (:registry (:app.acl/client env)) {:user/id "someone-else"}))
                                   {:user/id id} view))))
        (is (= [id] (d/q '[:find [?id ...] :where [?e :user/id ?id]] (d/db conn))))
        (let [schema-attrs (set (d/q '[:find [?a ...] :where [?e :db/ident ?a]] (d/db conn)))]
          (is (not-any? schema-attrs [:user/password :user/cities])))))))

(deftest mutation-data-drives-public-feedback-without-an-entity-identity
  (with-system
    (fn [env _ conn]
      (let [output [:user/authenticated? :user/errors :user/>auth-result]
            run (fn [operation params output]
                  (get (p.eql/process env {} [{(list operation params) output}]) operation))
            registered (run 'app.mutations.user/register
                            {:email "demo@example.test" :password "long-enough-password" :cities ["A" "B"]}
                            (conj output :user/id))
            id (:user/id registered)
            failed (run 'app.mutations.user/login {:email "demo@example.test" :password "wrong"} output)
            missing (run 'app.mutations.user/login {:email "missing@example.test" :password "wrong"} output)
            logged-in (run 'app.mutations.user/login
                           {:email "demo@example.test" :password "long-enough-password"}
                           (conj output :user/id))]
        (is (= {:user/registration-cities [{:city/id "A" :city/name "A"}
                                           {:city/id "B" :city/name "B"}
                                           {:city/id "C" :city/name "C"}]}
               (p.eql/process env {} [{:user/registration-cities [:city/id :city/name]}])))
        (is (= id (:user/id (user/by-email env "demo@example.test"))))
        (is (string? id))
        (let [result (mutations/login env {:email "demo@example.test" :password "long-enough-password"})
              transform (first (::eql/response-transforms (meta result)))
              session (:session (transform {:status 200}))]
          (is (= {:user/id id :user/authenticated? true :user/errors []} result))
          (is (= {:user/id id} session))
          (is (= {:recreate true} (meta session))))
        (doseq [result [registered logged-in]]
          (is (= {:user/id id :user/authenticated? true :user/errors []}
                 (dissoc result :user/>auth-result)))
          (is (= "auth-result" (get-in result [:user/>auth-result 1 :id])))
          (is (= "window.location.assign('/')"
                 (get-in result [:user/>auth-result 1 :data-init]))))
        (doseq [result [failed missing]]
          (is (= #{:user/authenticated? :user/errors :user/>auth-result} (set (keys result))))
          (is (false? (:user/authenticated? result)))
          (is (= ["Неверная электронная почта или пароль."] (:user/errors result)))
          (is (= "auth-result" (get-in result [:user/>auth-result 1 :id])))
          (is (str/includes? (pr-str (:user/>auth-result result)) (first (:user/errors result))))
          (is (not (str/includes? (get-in result [:user/>auth-result 1 :data-init]) "window.location"))))
        (is (= failed missing))
        (let [idents (set (d/q '[:find [?ident ...] :where [?e :db/ident ?ident]] (d/db conn)))]
          (is (not-any? idents [:user/authenticated? :user/errors :user/>auth-result])))
        (doseq [untrusted [env (assoc env :scriptorium.reitit/request {:identity {:user/id id}})]]
          (is (forbidden? #(p.eql/process untrusted {} (call 'app.mutations.user/logout {})))))
        (let [scoped (assoc env :scriptorium.eacl/subject
                            (eacl/resource (:registry (:app.acl/client env)) {:user/id id}))
              result (get (p.eql/process scoped {} (call 'app.mutations.user/logout {}))
                          'app.mutations.user/logout)]
          (is (= "auth-result" (get-in result [:user/>auth-result 1 :id])))
          (is (= "window.location.assign('/login')"
                 (get-in result [:user/>auth-result 1 :data-init]))))))))

(deftest password-verification-and-origin-boundary
  (let [secret "correct horse battery staple"
        a (password/password-hash secret)
        b (password/password-hash secret)]
    (is (not= a b))
    (is (password/password-matches? secret a))
    (is (not (password/password-matches? "wrong" a)))
    (is (not (password/password-matches? secret "broken"))))
  (is (password/password-matches?
       "long-enough-password"
       "pbkdf2-sha256$600000$Js9orPHK2hL3gdyRR66yMQ==$Jt5mkBLX98b/Zamw9JZJganxqD2fA26DOnz5fTuFqtM="))
  (doseq [origin [nil "null" "https://evil.test" "https://app.test.evil.test" "http://app.test" "https://app.test/"]]
    (is (not (middleware/same-origin? {:scheme :https :headers {"host" "app.test" "origin" origin}}))))
  (is (middleware/same-origin? {:scheme :https :headers {"host" "app.test:8443" "origin" "https://app.test:8443"}})))

(deftest registration-sessions-and-revocation
  (with-system
    (fn [env handler conn]
      (let [secret "long-enough-password"
            signup #(request handler :post "/eql" nil
                             (call 'app.mutations.user/register {:email % :password secret :cities ["A" "B"]})
                             "https://app.test")
            first-response (signup " One@Example.Test ")
            first-cookie (cookie first-response)
            second-cookie (cookie (signup "two@example.test"))
            login-query (call 'app.mutations.user/login {:email "one@example.test" :password secret})
            rotated-response (request handler :post "/eql" first-cookie login-query "https://app.test")
            rotated-cookie (cookie rotated-response)
            one (user/by-email env "one@example.test")
            scoped (assoc env :scriptorium.eacl/subject
                          (eacl/resource (:registry (:app.acl/client env)) {:user/id (:user/id one)}))]
        (is (= 200 (:status first-response)))
        (is (str/starts-with? (get-in first-response [:headers "Content-Type"]) "text/event-stream"))
        (is (str/includes? (:body first-response) "datastar-patch-elements"))
        (is (not (str/includes? (:body first-response) secret)))
        (doseq [attribute ["HttpOnly" "SameSite=Lax" "Secure"]]
          (is (some #(str/includes? % attribute) (get-in first-response [:headers "Set-Cookie"]))))
        (is (not= first-cookie rotated-cookie))
        (is (= 303 (:status (request handler :get "/" first-cookie nil nil))))
        (is (= "one@example.test" (get-in (request handler :get "/" rotated-cookie nil nil) [:body :user/email])))
        (is (= "two@example.test" (get-in (request handler :get "/" second-cookie nil nil) [:body :user/email])))
        (is (acl/can-view? scoped {:route/id "AB"}))
        (is (not (acl/can-view? scoped {:route/id "AC"})))
        (is (password/password-matches? secret (:user/password-hash one)))
        (is (= 403 (:status (request handler :post "/eql" rotated-cookie
                                     (call 'app.mutations.user/logout {}) nil))))
        (is (= 303 (:status (request handler :get "/route/secret.css" nil nil nil))))
        (is (= 200 (:status (request handler :post "/eql" rotated-cookie
                                     (call 'app.mutations.user/logout {}) "https://app.test"))))
        (is (= 303 (:status (request handler :get "/" rotated-cookie nil nil))))
        (is (= 200 (:status (request handler :get "/" second-cookie nil nil))))
        (is (= "no-store" (get-in (request handler :get "/" second-cookie nil nil) [:headers "Cache-Control"])))
        (is (= 400 (:status (request handler :post "/eql" nil
                                     (call 'app.mutations.user/login
                                           {:email "one@example.test" :password secret :cities ["A"]})
                                     "https://app.test"))))
        (is (= 400 (:status (request handler :post "/eql" nil
                                     (call 'app.mutations.user/not-a-mutation {})
                                     "https://app.test"))))
        (is (= 400 (:status (request handler :post "/eql" second-cookie
                                     [:user/password] "https://app.test"))))
        (is (= 400 (:status (request handler :post "/eql" second-cookie
                                     (str (pr-str (call 'app.mutations.user/logout {}))
                                          " (app.mutations.user/logout {})")
                                     "https://app.test"))))
        (is (= 200 (:status (request handler :get "/" second-cookie nil nil))))
        (is (= 2 (d/q '[:find (count ?e) . :where [?e :user/id]] (d/db conn))))))))

(deftest registration-validates-and-duplicate-races-do-not-overwrite
  (with-system
    (fn [env handler conn]
      (let [bad (request handler :post "/eql" nil
                         (call 'app.mutations.user/register {:email "bad" :password "short" :cities ["unknown"]})
                         "https://app.test")]
        (is (= 200 (:status bad)))
        (is (nil? (get-in bad [:headers "Set-Cookie"])))
        (is (str/includes? (:body bad) "auth-result"))
        (is (str/includes? (:body bad) "Укажите корректный адрес электронной почты."))
        (is (str/includes? (:body bad) "Пароль должен содержать от 6 до 256 символов."))
        (is (not (str/includes? (:body bad) "window.location.assign")))
        (is (empty? (d/q '[:find [?e ...] :where [?e :user/id]] (d/db conn)))))
      (let [start (promise)
            signup #(future @start
                            (p.eql/process env {}
                                           (call 'app.mutations.user/register
                                                 {:email " Demo@Example.Test " :password "long-enough-password"
                                                  :cities ["A" "B"]})))
            a (signup) b (signup)]
        (deliver start true)
        (let [results [@a @b]
              user (user/by-email env "demo@example.test")]
          (is (= 1 (d/q '[:find (count ?e) . :where [?e :user/id]] (d/db conn))))
          (is (= "demo@example.test" (:user/email user)))
          (is (password/password-matches? "long-enough-password" (:user/password-hash user)))
          (is (= 1 (count (filter #(str/includes? (pr-str %) "Переход") results)))))))))
