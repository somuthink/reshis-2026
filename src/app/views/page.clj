(ns app.views.page)

(defn entity [title content]
  [:main {:class "mx-auto w-full max-w-5xl px-4 pb-8 sm:px-8"}
   [:header {:class "flex flex-wrap items-baseline gap-x-5 gap-y-2 py-7 text-white"}
    [:a {:href "/" :class "text-base text-white/90 underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4"}
     "← Расчёт"]
    [:h1 {:class "text-2xl font-medium tracking-tight"} title]]
   [:div {:class "clay-panel overflow-hidden p-5 sm:p-8"} content]])
