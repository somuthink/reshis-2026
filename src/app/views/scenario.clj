(ns app.views.scenario
  (:require [jsonista.core :as json]
            [ring.util.codec :as codec]
            [scriptorium.datastar :as ds]
            [scriptorium.pathom.ui :as ui]))

(defn result
  [{:scenario/keys [id estimate route distribution]}]
  (let [{:keys [error date seats demand sales]} estimate]
    [:section {:class "min-w-0 border-t border-sky-100 p-5 sm:p-8 lg:border-t-0 lg:border-l" :aria-live "polite"}
     (if error
       [:div {:class "border-l-2 border-amber-600 pl-4"}
        [:h2 {:class "mb-2 text-lg font-medium text-slate-900"} "Прогноз недоступен"]
        [:p {:class "text-base leading-relaxed text-slate-600" :role "alert"} error]]
       [:div
        [:div {:class "mb-6 flex flex-wrap items-baseline justify-between gap-2"}
         [:h2 {:class "text-lg font-medium text-slate-900"}
          (get-in route [:route/origin-city :city/>link])
          " — "
          (get-in route [:route/destination-city :city/>link])]
         [:div {:class "flex items-baseline gap-4 text-sm"}
          [:p {:class "font-mono text-slate-500"} date]
          [:a {:href (str "/routes/" (codec/url-encode (:route/id route)))
               :class "text-sky-700 underline underline-offset-4 hover:text-sky-950 focus-visible:outline-2 focus-visible:outline-offset-4"}
           "Маршрут"]]]
        [:dl
         [:div {:class "mb-6"}
          [:dt {:class "text-base text-slate-600"} "Прогноз продаж"]
          [:dd {:class "mt-2 text-5xl font-semibold tracking-tight text-sky-950 tabular-nums sm:text-6xl"}
           (format "%.2f" sales)]]
         [:div {:class "flex items-baseline justify-between gap-4 border-t border-sky-100 py-3 text-base"}
          [:dt {:class "text-slate-600"} "Спрос"]
          [:dd {:class "font-mono tabular-nums"} (format "%.2f" demand)]]
         [:div {:class "flex items-baseline justify-between gap-4 border-t border-sky-100 py-3 text-base"}
          [:dt {:class "text-slate-600"} "Мест"]
          [:dd {:class "font-mono tabular-nums"} (str seats)]]]
        [:figure {:class "mt-5 border-t border-sky-100 pt-5"}
         [:h3 {:class "text-base font-medium text-slate-700"} "Распределение спроса"]
         [:demand-chart {:id (str "demand-chart-" id)
                         :points (json/write-value-as-string distribution)
                         :capacity (str seats)
                         :role "img"
                         :aria-label (str "Квантили спроса от 10 до 90%. Медиана: "
                                          (format "%.2f" demand) ". Мест: " seats ".")}]
         [:figcaption {:class "text-xs leading-relaxed text-slate-500"}
          "Между соседними квантилями — 10% вероятности. Медиана выделена."]
         [:table {:class "sr-only"}
          [:caption "Квантили спроса при выбранной цене"]
          [:thead [:tr [:th {:scope "col"} "Квантиль"] [:th {:scope "col"} "Спрос"]]]
          [:tbody
           (for [{:keys [quantile demand]} distribution]
             [:tr [:td (str (Math/round (* 100.0 quantile)) "%")]
              [:td (format "%.2f" demand)]])]]]])]))

(defn form
  [{:scenario/keys [id >from >to >date >price]}]
  [:main {:class "mx-auto w-full max-w-5xl px-4 pb-8 sm:px-8"}
   [:header {:class "flex flex-wrap items-baseline gap-x-5 gap-y-1 py-7 text-white"}
    [:p {:class "text-base font-medium text-white/90"} "Решись Авиа"]
    [:h1 {:class "text-2xl font-medium tracking-tight"} "Прогноз продаж"]]
   [:div {:class "clay-panel grid grid-cols-1 overflow-hidden lg:grid-cols-2"}
    [:form (merge {:class "grid min-w-0 content-start gap-5 p-5 sm:p-8"}
                  (ds/on :submit
                         [{(list 'app.scenario/evaluate {:from '$from :to '$to :date '$date :price '$price})
                           [:scenario/>result]}]
                         {}))
     [:div (merge {:class "grid gap-4 sm:grid-cols-2"}
                  (ds/on :change
                         [(list :route/origin-cities {:to '$to})
                          (list :route/destination-cities {:from '$from})
                          :scenario/>from :scenario/>to]
                         {:scenario/id id}))
      [:div {:class "grid min-w-0 gap-1.5"}
       [:label {:class "text-base text-slate-600" :for (ui/view-id :scenario/>from id)} "Откуда"] >from]
      [:div {:class "grid min-w-0 gap-1.5"}
       [:label {:class "text-base text-slate-600" :for (ui/view-id :scenario/>to id)} "Куда"] >to]]
     [:div {:class "grid gap-4 sm:grid-cols-2"}
      [:div {:class "grid min-w-0 gap-1.5"}
       [:label {:class "text-base text-slate-600" :for (ui/view-id :scenario/>date id)} "Дата"] >date]
      [:div {:class "grid min-w-0 gap-1.5"}
       [:label {:class "text-base text-slate-600" :for (ui/view-id :scenario/>price id)} "Цена, ₽"] >price]]
     [:button {:class "clay-button mt-1 h-14 px-6 text-lg font-medium text-white focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-600"
               :type "submit"}
      "Рассчитать"]]
    [:section {:class "min-w-0 border-t border-sky-100 p-5 sm:p-8 lg:border-t-0 lg:border-l"
               :id (ui/view-id :scenario/>result id) :aria-live "polite"}
     [:h2 {:class "text-base text-slate-500"} "Прогноз продаж"]
     [:p {:class "mt-2 text-5xl text-slate-300"} "—"]]]])
