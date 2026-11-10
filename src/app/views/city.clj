(ns app.views.city
  (:require
   [app.views.page :as page]
   [ring.util.codec :as codec]))

(defn link [{:city/keys [id name]}]
  [:a {:href (str "/cities/" (codec/url-encode id))
       :class "text-sky-700 underline decoration-sky-300 underline-offset-4 hover:text-sky-900 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-600"}
   name])

(defn- route-list [title routes empty-message]
  [:section {:class "space-y-3"}
   [:h2 {:class "text-lg font-medium text-slate-800"} title]
   (if (seq routes)
     (into [:ul {:class "space-y-2 text-base"}]
           (map (fn [route] [:li (:route/>link route)]))
           (sort-by (juxt :route/name :route/id) routes))
     [:p {:class "text-base text-slate-600"} empty-message])])

(defn detail [{:city/keys [id name outgoing-routes incoming-routes]}]
  (page/entity
   name
   [:div {:class "space-y-6 text-base"}
    [:p {:class "text-slate-600"} "Код города: " [:span {:class "font-medium text-slate-800"} id]]
    (route-list "Вылеты" outgoing-routes "Нет исходящих маршрутов.")
    (route-list "Прилёты" incoming-routes "Нет входящих маршрутов.")]))
