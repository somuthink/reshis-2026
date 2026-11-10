(ns app.views.route
  (:require [app.views.page :as page]
            [ring.util.codec :as codec]))

(defn link
  [{:route/keys [id name]}]
  [:a {:href (str "/routes/" (codec/url-encode id))
       :class "font-medium text-sky-800 underline decoration-sky-300 underline-offset-4 hover:text-sky-950 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-600"}
   name])

(defn detail
  [{:route/keys [id name origin-city destination-city base-price cost-per-passenger
                 price-sensitivity price-min price-max forecasts]}]
  (page/entity
   (or name id)
   [:div {:class "min-w-0 space-y-8 text-base text-sky-950"}
    [:dl {:class "grid min-w-0 grid-cols-[minmax(0,1fr)_minmax(0,1fr)] gap-x-4 gap-y-3 [&_dt]:text-slate-600 [&_dd]:min-w-0 [&_dd]:text-right [&_dd]:tabular-nums [&_dd]:break-words"}
     [:dt "Откуда"] [:dd (:city/>link origin-city)]
     [:dt "Куда"] [:dd (:city/>link destination-city)]
     [:dt "Базовая цена, ₽"] [:dd (format "%.2f" base-price)]
     [:dt "Затраты на пассажира, ₽"] [:dd (format "%.2f" cost-per-passenger)]
     [:dt "Чувствительность спроса, пасс./₽"] [:dd (str price-sensitivity)]
     [:dt "Минимальная цена, ₽"] [:dd (format "%.2f" price-min)]
     [:dt "Максимальная цена, ₽"] [:dd (format "%.2f" price-max)]]
    [:section {:class "min-w-0" :aria-labelledby "route-forecasts"}
     [:h2 {:id "route-forecasts" :class "mb-4 text-lg font-semibold"} "Прогнозы по дням"]
     [:table {:class "w-full table-fixed text-left text-base"}
      [:caption {:class "sr-only"} "Вместимость и медианный спрос при базовой цене"]
      [:thead {:class "border-b border-sky-200 text-sm text-slate-600"}
       [:tr
        [:th {:scope "col" :class "w-2/5 pb-3 pr-2 font-medium"} "Дата"]
        [:th {:scope "col" :class "pb-3 px-1 text-right font-medium"} "Мест"]
        [:th {:scope "col" :class "pb-3 pl-1 text-right font-medium"} "Медианный спрос, пасс."]]]
      [:tbody (for [{:forecast/keys [>row]} (sort-by :forecast/date forecasts)] >row)]]]]))

