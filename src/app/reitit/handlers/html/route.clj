(ns app.reitit.handlers.html.route
  (:require [app.views.fields]
            [app.views.page :as layout]
            [scriptorium.reitit.page :as page]))

(page/defpage index
  [request {:scenario/keys [>form]}]
  {:scenario/id "current"}
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout/document request >form true)})

(page/defpage detail
  [request {:route/keys [>view]}]
  {:route/id (get-in request [:parameters :path :route/id])}
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout/document request >view false)})

(page/defpage city
  [request {:city/keys [>view]}]
  {:city/id (get-in request [:parameters :path :city/id])}
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (layout/document request >view false)})
