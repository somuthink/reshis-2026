(ns app.reitit.handlers.html.user
  (:require [app.views.fields]
            [app.views.page :as layout]
            [scriptorium.reitit.page :as page]))

(defn- temporary-user []
  {:user/id (str "temp-" (random-uuid)) :user/email ""})

(defn- render [request form]
  {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout/document request form false)})

(page/defpage login
  [request {:user/keys [>login-form]}]
  (temporary-user)
  (render request >login-form))

(page/defpage register
  [request {:user/keys [>register-form]}]
  (temporary-user)
  (render request >register-form))

(page/defpage account
  [request {:user/keys [>form]}]
  {:user/id (get-in request [:identity :user/id])}
  (render request >form))
