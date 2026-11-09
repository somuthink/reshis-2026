(ns app.views.route)

(defn detail
  [{:route/keys [id name base-price cost-per-passenger price-sensitivity price-min price-max forecasts]}]
  [:main {:class "mx-auto my-8 max-w-3xl rounded-3xl bg-white/95 p-6 shadow-sm sm:p-8"}
   [:h1 {:class "mb-6 font-display text-3xl"} (or name id)]
   [:dl {:class "grid grid-cols-2 gap-3 text-sm [&_dt]:text-slate-500 [&_dd]:font-medium"}
    [:dt "Base price"] [:dd (str base-price)]
    [:dt "Cost per passenger"] [:dd (str cost-per-passenger)]
    [:dt "Price sensitivity"] [:dd (str price-sensitivity)]
    [:dt "Minimum price"] [:dd (str price-min)]
    [:dt "Maximum price"] [:dd (str price-max)]]
   [:h2 {:class "mt-8 mb-4 text-lg font-medium"} "Forecasts"]
   [:table {:class "w-full text-left text-sm [&_td]:py-2 [&_th]:pb-3 [&_th]:font-medium"}
    [:thead [:tr [:th "Date"] [:th "Seats"] [:th "Median demand"]]]
    [:tbody (for [{:forecast/keys [>row]} forecasts] >row)]]])

