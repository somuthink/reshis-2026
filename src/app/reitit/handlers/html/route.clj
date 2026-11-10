(ns app.reitit.handlers.html.route
  (:require [dev.onionpancakes.chassis.core :as c]
            [app.views.fields]
            [scriptorium.reitit.page :as page]))

(defn- document [view]
  (c/html
   [:html {:lang "ru"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "Прогноз продаж авиабилетов"]
     [:script {:type "module" :src "/js/demand-chart.js?v=rocket"}]
     [:link {:rel "stylesheet" :href "/css/app.css"}]]
    [:body view]]))

(page/defpage index
  [_request {:scenario/keys [>form]}]
  {:scenario/id "current"}
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (document >form)})

(page/defpage detail
  [request {:route/keys [>view]}]
  {:route/id (get-in request [:parameters :path :route/id])}
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (document >view)})

(page/defpage city
  [request {:city/keys [>view]}]
  {:city/id (get-in request [:parameters :path :city/id])}
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (document >view)})
