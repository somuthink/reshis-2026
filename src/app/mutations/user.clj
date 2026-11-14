(ns app.mutations.user
  (:require [app.acl :as acl]
            [app.password :as password]
            [app.resolvers.user :as user]
            [com.wsscode.pathom3.connect.operation :as pco]
            [datahike.api :as d]
            [integrant.core :as ig]
            [scriptorium.pathom.params :as params]
            [scriptorium.reitit.eql :as eql]))

(defn- result [errors]
  {:user/authenticated? false :user/errors errors})

(defn- signed-in-result [user]
  (eql/augment-response {:user/id (:user/id user) :user/authenticated? true :user/errors []}
                        #(assoc % :session (with-meta {:user/id (:user/id user)} {:recreate true}))))

;; Forms validate raw field values below so ordinary mistakes return inline feedback.
(pco/defmutation login [env {:keys [email password]}]
  {::params/schema [:map {:closed true}
                    [:email {:optional true} :any]
                    [:password {:optional true} :any]]
   :scriptorium/public? true
   ::pco/output [:user/id :user/authenticated? :user/errors]}
  (let [user (user/by-email env email)]
    (if (and (string? password) (<= (count password) 256)
             (password/password-matches? password (:user/password-hash user))
             (:user/id user))
      (signed-in-result user)
      (result ["Неверная электронная почта или пароль."]))))

(defn- registration-errors [env email password selected]
  (let [valid-selection? (and (sequential? selected) (every? string? selected))]
    (cond-> []
      (or (> (count email) 254) (not (re-matches #"[^\s@]+@[^\s@]+\.[^\s@]+" email)))
      (conj "Укажите корректный адрес электронной почты.")
      (not (and (string? password) (<= 12 (count password) 256)))
      (conj "Пароль должен содержать от 12 до 256 символов.")
      (not valid-selection?)
      (conj "Выберите города из списка.")
      (and valid-selection?
           (not (every? (set (map :city/id (user/cities env))) selected)))
      (conj "Выбран неизвестный город. Обновите страницу и повторите выбор."))))

(def ^:private duplicate-email-errors ["Этот адрес электронной почты уже зарегистрирован."])

(defn- duplicate-email? [{:scriptorium.datahike/keys [env->db] :as env} error]
  (some (fn [cause]
          (let [{:keys [error attribute]} (ex-data cause)]
            (and (= :transact/unique error)
                 (= :user/email (if (number? attribute)
                                  (:db/ident (d/pull (env->db env) [:db/ident] attribute))
                                  attribute)))))
        (take-while some? (iterate ex-cause error))))

(defn- create-user! [{:scriptorium.datahike/keys [transaction] :as env} email password]
  (let [user {:user/id (str (random-uuid)) :user/email email
              :user/password-hash (password/password-hash password)}]
    (try
      ;; Unique VALUE rejects racing signups instead of upserting another user.
      (transaction [user])
      {:user (select-keys user [:user/id :user/email])}
      (catch Exception error
        (if (duplicate-email? env error)
          {:errors duplicate-email-errors}
          (throw error))))))

(pco/defmutation register [env {:keys [email password cities]}]
  {::params/schema [:map {:closed true}
                    [:email {:optional true} :any]
                    [:password {:optional true} :any]
                    [:cities {:optional true} :any]]
   :scriptorium/public? true
   ::pco/output [:user/id :user/authenticated? :user/errors]}
  (let [email (user/canonical-email email)
        errors (registration-errors env email password cities)]
    (cond
      (seq errors) (result errors)
      (user/by-email env email) (result duplicate-email-errors)
      :else
      (let [selected (vec (sort (set cities)))
            resources (into (mapv #(hash-map :city/id %) selected)
                            (user/registration-routes env selected))
            {:keys [user errors]} (create-user! env email password)]
        (if errors
          (result errors)
          (do
            (acl/grant-view! env {:user/id (:user/id user)} resources)
            (signed-in-result user)))))))

(pco/defmutation logout [_ _]
  {::params/schema [:map {:closed true}]
   :eacl/permission :view
   :eacl/resource acl/subject-resource
   ::pco/output [:user/authenticated? :user/errors]}
  (eql/augment-response (result []) #(assoc % :session nil)))

(defmethod ig/init-key ::mutations [_ _]
  [login register logout])
