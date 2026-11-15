(ns app.views.scenario
  (:require [scriptorium.datastar :as ds]
            [scriptorium.pathom.ui :as ui]))

(defn chart
  [{:scenario/keys [distribution estimate]}]
  (let [{:keys [error seats]} estimate]
    (when (and (not error) (seq distribution))
      (let [demands (mapv :demand distribution)
            low (min seats (first demands))
            high (max seats (peek demands))
            span (max 1.0 (- high low))
            x (fn [demand] (+ 200 (* 250 (/ (- demand (+ low (/ (- high low) 2))) span))))
            y (fn [quantile] (+ 180 (* 170 quantile)))
            median (:demand (nth distribution 4))]
        [:figure {:class "demand-distribution"}
         [:dl {:class "grid grid-cols-3 gap-2 text-sky-950 tabular-nums"}
          (for [[index label] [[0 "Слабый спрос"] [4 "Медиана"] [8 "Высокий спрос"]]]
            [:div {:class (if (= index 4) "text-center text-amber-700" (if (zero? index) "text-left" "text-right"))}
             [:dt {:class "min-h-12 text-base text-slate-600"}
              label [:span {:class "sr-only"} (str ", квантиль " (* 10 (inc index)) "%")]]
             [:dd {:class "mt-1 text-2xl font-semibold tracking-tight sm:text-3xl"}
              (format "%.2f" (nth demands index))]])]
         [:svg {:viewBox "0 0 400 395" :class "mt-4 block w-full" :role "img"
                :aria-label (str "Спрос: центральные 80% от " (format "%.2f" (first demands))
                                 " до " (format "%.2f" (peek demands))
                                 ". Медиана " (format "%.2f" median) ". Мест: " seats
                                 ". Нижний график: вероятность спроса выше отметки.")}
          [:rect {:x (x seats) :y 44 :width (- 355 (x seats)) :height 306 :fill "var(--warning)" :opacity 0.07}]
          (for [[left right] (partition 2 1 demands)]
            [:rect {:x (x left) :y 55 :width (- (x right) (x left)) :height 30
                    :fill "var(--accent)" :opacity 0.65 :stroke "var(--surface)"}
             [:title (str "10% вероятности: " (format "%.2f" left) "–" (format "%.2f" right))]])
          (for [demand demands]
            [:line {:x1 (x demand) :x2 (x demand) :y1 55 :y2 85 :stroke "var(--surface)"}])
          [:line {:x1 (x median) :x2 (x median) :y1 48 :y2 92 :stroke "var(--warning)" :stroke-width 4}]
          [:text {:x 200 :y 120 :text-anchor "middle"} "Каждый сегмент — 10% вероятности"]
          (for [probability [0 50 100]
                :let [py (- 350 (* 1.7 probability))]]
            [:g
             [:line {:x1 60 :x2 340 :y1 py :y2 py :stroke "var(--border)"}]
             [:text {:x 48 :y (+ py 5) :text-anchor "end"} (str probability "%")]])
          (for [{:keys [quantile demand]} distribution]
            [:circle {:cx (x demand) :cy (y quantile) :r (if (= quantile 0.5) 7 5)
                      :fill (if (= quantile 0.5) "var(--warning)" "var(--accent)")
                      :stroke "var(--surface)" :stroke-width 2}
             [:title (str (Math/round (* 100 (- 1.0 quantile))) "%: спрос выше " (format "%.2f" demand))]])
          (for [[top bottom] [[32 99] [171 350]]]
            [:line {:class "capacity" :x1 (x seats) :x2 (x seats) :y1 top :y2 bottom
                    :stroke "var(--fg)" :stroke-width 1.5 :stroke-dasharray "4 4"}])
          [:text {:x (x seats) :y 23 :text-anchor (if (> (x seats) 240) "end" "start")
                  :class "font-semibold"} (str seats " мест")]
          (for [tick (distinct [low (+ low (/ (- high low) 2)) high])]
            [:text {:x (x tick) :y 374 :text-anchor "middle"} (format (if (< span 10) "%.2f" "%.0f") (double tick))])
          [:text {:x 200 :y 394 :text-anchor "middle"} "Спрос, чел."]]
         [:table {:class "sr-only"}
          [:thead [:tr [:th {:scope "col"} "Квантиль"] [:th {:scope "col"} "Спрос"]
                   [:th {:scope "col"} "Вероятность большего спроса"]]]
          [:tbody
           (for [{:keys [quantile demand]} distribution]
             [:tr [:th {:scope "row"} (str (Math/round (* 100 quantile)) "%")]
              [:td (format "%.2f" demand)]
              [:td (str (Math/round (* 100 (- 1.0 quantile))) "%")]])]]]))))

(defn result
  [{:scenario/keys [estimate route >chart]}]
  (let [{:keys [error date seats sales]} estimate]
    [:section {:class "clay-panel min-w-0 p-5 sm:p-8"
               :data-show "$from && $to && $date && Number.isFinite($price) && $price >= 0"
               :aria-live "polite"}
     (if error
       [:div {:class "border-l-2 border-amber-600 pl-4"}
        [:h2 {:class "mb-2 text-xl font-medium text-slate-900"} "Прогноз недоступен"]
        [:p {:class "text-lg text-slate-600" :role "alert"} error]]
       [:div
        [:header {:class "mb-6"}
         [:h2 {:class "text-xl font-medium text-sky-950"} (:route/>link route)]
         [:p {:class "mt-1 text-base text-slate-500"} date]]
        [:dl {:class "mb-8"}
         [:dt {:class "text-lg text-slate-600"} "Купят билеты"]
         [:dd {:class "mt-2 flex flex-wrap items-baseline gap-x-3 text-sky-950 tabular-nums"}
          [:span {:class "text-5xl font-semibold tracking-tight sm:text-6xl"} (format "%.2f" sales)]
          [:span {:class "text-xl text-slate-500"} (str "из " seats " мест")]]]
        >chart])]))

(defn form
  [{:scenario/keys [id >from >to >date >price]}]
  [:main {:class "mx-auto w-full max-w-5xl px-4 pb-8 sm:px-8"}
   [:header {:class "flex flex-wrap items-baseline gap-x-5 gap-y-1 py-7 text-white"}
    [:p {:class "text-base font-medium text-white/90"} "Решись Авиа"]
    [:h1 {:class "text-2xl font-medium tracking-tight"} "Прогноз продаж"]]
   [:div {:class "grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]"}
    [:form {:class "clay-panel grid min-w-0 content-start gap-5 p-5 sm:p-8"
            :data-on:submit "evt.preventDefault()"
            :data-on:input__debounce.300ms
            (str "evt.target.name !== 'scenario/target' && el.checkValidity() && $from && $to && $date ? "
                 (get (ds/on :change
                             [{(list 'app.mutations.scenario/evaluate {:from '$from :to '$to :date '$date :price '$price})
                               [:scenario/>result]}] {})
                      :data-on:change)
                 " : null")}
     [:div (merge {:class "grid gap-4 sm:grid-cols-2"}
                  (ds/on :change
                         [(list :route/origin-cities {:to '$to})
                          (list :route/destination-cities {:from '$from})
                          (list :route/forecast-dates {:from '$from :to '$to})
                          :scenario/>from :scenario/>to (list :scenario/>date {:label? false}) :scenario/>price]
                         {:scenario/id id}))
      [:div {:class "grid min-w-0 gap-1.5"} >from]
      [:div {:class "grid min-w-0 gap-1.5"} >to]]
     [:div {:class "grid gap-4"}
      [:div {:class "grid min-w-0 gap-1.5"} >date]
      [:div {:class "grid min-w-0 gap-1.5"} >price]]
     [:fieldset {:class "grid gap-3 border-t border-sky-100 pt-5" :data-signals "{target: 1}"}
      [:legend {:class "text-lg font-medium text-sky-950"} "Подобрать цену"]
      [:label {:class "grid gap-2 text-slate-700"}
       "Продажи по медиане, пассажиров"
       [:input {:type "number" :name "scenario/target" :min 1 :step 1 :class "field-control" :data-bind "target"}]]
      [:button (merge {:type "button"
                       :class "rounded-xl bg-sky-700 px-4 py-3 text-lg text-white hover:bg-sky-800 focus-visible:outline-2"
                       :data-attr:disabled "!$from || !$to || !$date || !Number.isInteger($target) || $target < 1"}
                      (ds/on :click
                             [{(list 'app.mutations.scenario/set-target-sales {:from '$from :to '$to :date '$date :price '$price :target '$target})
                               [:scenario/>price :scenario/>result]}] {}))
       "Рассчитать цену"]]]
    [:section {:class "clay-panel min-w-0 p-5 sm:p-8"
               :id (ui/view-id :scenario/>result id) :aria-live "polite"}
     [:h2 {:class "text-base text-slate-500"} "Прогноз продаж"]
     [:p {:class "mt-2 text-5xl text-slate-300"} "—"]]]])
