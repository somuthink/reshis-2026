(ns app.resolvers.scenario
  (:require [app.acl :as acl]
            [com.wsscode.pathom3.connect.operation :as pco]
            [datahike.api :as d]))

(defn- unique-match [matches]
  (when (next matches)
    (throw (ex-info "Ambiguous scenario selection" {:matches matches})))
  (let [[match] matches] match))

(defn route-selection
  [{:scriptorium.datahike/keys [env->db] :as env} {:scenario/keys [from to]}]
  (when-let [route
             (unique-match
              (d/q '[:find [(pull ?route [:route/id]) ...]
                     :in $ ?from ?to
                     :where [?origin :city/id ?from] [?destination :city/id ?to]
                     [?route :route/origin-city ?origin] [?route :route/destination-city ?destination]]
                   (env->db env) (:city/id from) (:city/id to)))]
    (acl/require-view! env route)))

(defn forecast-selection
  [{:scriptorium.datahike/keys [env->db] :as env} {:scenario/keys [date route]}]
  (when route
    (acl/require-view! env route)
    (when-let [forecast
               (unique-match
                (d/q '[:find [(pull ?forecast [:forecast/id]) ...]
                       :in $ ?route-id ?date
                       :where [?route :route/id ?route-id]
                       [?route :route/forecasts ?forecast] [?forecast :forecast/date ?date]]
                     (env->db env) (:route/id route) (str date)))]
      (acl/require-view! env forecast))))

(defn- demand-at-price [baseline base-price sensitivity price]
  (- baseline (* sensitivity (- price base-price))))

(defn estimate-sales
  [median-demand seats base-price sensitivity price]
  (let [demand (demand-at-price median-demand base-price sensitivity price)]
    {:demand demand :sales (min (double seats) (max 0.0 demand))}))

(defn target-price
  [env {:scenario/keys [estimate route]}]
  (let [target (:target (pco/params env))
        {:keys [error seats median-demand]} estimate
        {:route/keys [base-price price-sensitivity price-min price-max]} route]
    (cond
      error {:error error}
      (or (<= target 0) (> target seats)) {:error (str "Укажите число пассажиров от 1 до " seats ".")}
      (not (pos? price-sensitivity)) {:error "Нельзя подобрать цену при нулевой чувствительности спроса."}
      :else
      (let [price (+ base-price (/ (- median-demand target) price-sensitivity))]
        (if (<= price-min price price-max)
          {:price price}
          {:error "Цель недостижима в диапазоне цен маршрута."})))))

(defn distribution
  [{:scenario/keys [price route forecast]}]
  (when (and route forecast)
    (mapv (fn [[quantile attribute]]
            {:quantile quantile
             :demand (demand-at-price (get forecast attribute)
                                      (:route/base-price route)
                                      (:route/price-sensitivity route) price)})
          [[0.1 :forecast/demand-q10] [0.2 :forecast/demand-q20]
           [0.3 :forecast/demand-q30] [0.4 :forecast/demand-q40]
           [0.5 :forecast/demand-q50] [0.6 :forecast/demand-q60]
           [0.7 :forecast/demand-q70] [0.8 :forecast/demand-q80]
           [0.9 :forecast/demand-q90]])))

(defn estimate
  [{:scenario/keys [from to date price route forecast]}]
  (let [{:route/keys [name base-price price-sensitivity]} route
        {:forecast/keys [seats demand-q50]} forecast]
    (cond
      (nil? route) {:error (str "No route exists from " (:city/id from) " to " (:city/id to) ".")}
      (nil? forecast) {:error (str "No forecast exists for " date " on route " name ".")}
      :else (merge {:name name :date (str date) :price price :median-demand demand-q50 :seats seats}
                   (estimate-sales demand-q50 seats base-price price-sensitivity price)))))
