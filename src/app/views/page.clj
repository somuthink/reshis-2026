(ns app.views.page
  (:require [dev.onionpancakes.chassis.core :as c]
            [scriptorium.datastar :as ds]))

(defn document [request content]
  (c/html
   [:html {:lang "ru"}
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "Прогноз продаж авиабилетов"]
     [:script {:type "module"
               :src "/js/exceedance-chart.js"}]
     [:link {:rel "stylesheet" :href "/css/app.css"}]]
    [:body
     (when-let [email (get-in request [:identity :user/email])]
       [:nav {:aria-label "Аккаунт"
              :class "mx-auto flex w-full max-w-5xl flex-wrap items-center justify-end gap-4 px-4 pt-4 text-sm text-white sm:px-8"}
        [:a {:href "/account" :class "underline underline-offset-4"} email]
        [:button (merge {:type "button"
                         :class "rounded-lg border border-white/40 px-3 py-2 hover:bg-white/10 focus-visible:outline-2"}
                        (ds/on :click
                               [{(list 'app.mutations.user/logout {}) [:user/>auth-result]}] {}))
         "Выйти"]
        [:div {:id "auth-result" :role "alert"}]])
     content]]))

(defn entity [title content]
  [:main {:class "mx-auto w-full max-w-5xl px-4 pb-8 sm:px-8"}
   [:header {:class "flex flex-wrap items-baseline gap-x-5 gap-y-2 py-7 text-white"}
    [:a {:href "/" :class "text-base text-white/90 underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4"}
     "← Расчёт"]
    [:h1 {:class "text-2xl font-medium tracking-tight"} title]]
   [:div {:class "clay-panel overflow-hidden p-5 sm:p-8"} content]])
