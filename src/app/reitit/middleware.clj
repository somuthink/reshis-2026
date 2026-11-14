(ns app.reitit.middleware
  (:require [app.resolvers.user :as user]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [ring.middleware.session :as session]
            [ring.middleware.session.memory :as memory]
            [scriptorium.eacl :as eacl]))

(defn same-origin? [{:keys [scheme headers]}]
  (and (#{:http :https} scheme)
       (seq (get headers "host"))
       (= (get headers "origin") (str (name scheme) "://" (get headers "host")))))

(defn- asset? [{:keys [uri request-method]}]
  (and (#{:get :head} request-method)
       (some #(str/starts-with? uri %) ["/css/" "/js/" "/images/" "/fonts/"])
       (not (re-find #"(?:\.\.|[%\\])" uri))
       (not (str/ends-with? uri "/"))
       (some? (io/resource (str "public" uri)))))

(defn wrap-identity [handler env registry]
  (fn [request]
    (let [public-asset? (asset? request)
          identity (user/by-id env (get-in request [:session :user/id]))
          request (-> request
                      (dissoc :identity)
                      (update :scriptorium.pathom/env dissoc :scriptorium.eacl/subject)
                      (cond-> identity
                        (assoc :identity identity)
                        identity (assoc-in [:scriptorium.pathom/env :scriptorium.eacl/subject]
                                           (eacl/resource registry identity))))
          response (cond
                     (and (not (#{:get :head :options} (:request-method request)))
                          (not (same-origin? request)))
                     {:status 403 :body "Forbidden Origin"}

                     (and (nil? identity) (not public-asset?)
                          (not (#{"/login" "/register" "/eql"} (:uri request))))
                     {:status 303 :headers {"Location" "/login"} :body ""}

                     :else (handler request))]
      (cond-> (assoc response :session-cookie-attrs {:secure (= :https (:scheme request))})
        (not public-asset?) (assoc-in [:headers "Cache-Control"] "no-store")))))

(defmethod ig/init-key ::handler [_ {:keys [handler pathom-env registry]}]
  (session/wrap-session
   (wrap-identity handler pathom-env registry)
   {:store (memory/memory-store)
    :cookie-name "app-session"
    :cookie-attrs {:path "/" :http-only true :same-site :lax}}))
