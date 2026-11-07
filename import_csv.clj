#!/usr/bin/env bb
(ns import-csv
  (:require [clojure.data.csv :as csv]
            [datahike.pod :as d]
            [clojure.string :as str]))

(def quantiles (map #(str "0." % "_base_demand") (range 1 10)))

(defn rows [source]
  (let [content (if (str/starts-with? source "http")
                  (slurp (java.net.URL. source))
                  (slurp source))
        [header & records] (csv/read-csv (java.io.StringReader. content))]
    (map #(zipmap header %) records)))

(let [routes (rows (or (first *command-line-args*)
                       "https://gist.githubusercontent.com/Murkat-git/3ddc868889d3e0d37542d786c8b1aa74/raw/a934b1220da2b20d7f3eb397a7459fe78ea168ef/routes.csv"))
      forecasts (rows (or (second *command-line-args*)
                          "https://gist.githubusercontent.com/Murkat-git/3ddc868889d3e0d37542d786c8b1aa74/raw/seasonal_forecast.csv"))
      conn (d/connect {:store {:backend :file
                               :path "data/db"
                               :id #uuid "2385e499-2aed-44bd-a2a2-7b721d36f3d7"}
                       :schema-flexibility :write})]
  (d/transact conn
              (mapv (fn [{:strs [route name base_price cost_per_passenger price_sensitivity price_min price_max]}]
                      {:route/id route :route/name name
                       :route/base-price (parse-double base_price)
                       :route/cost-per-passenger (parse-double cost_per_passenger)
                       :route/price-sensitivity (parse-double price_sensitivity)
                       :route/price-min (parse-double price_min)
                       :route/price-max (parse-double price_max)})
                    routes))
  (d/transact conn
              (mapv (fn [row]
                      (merge {:forecast/id (random-uuid)
                              :forecast/route [:route/id (get row "route")]
                              :forecast/date (get row "date")
                              :forecast/seats (parse-long (get row "seats"))}
                             (into {} (map-indexed (fn [i column]
                                                     [(keyword "forecast" (str "demand-q" (* 10 (inc i))))
                                                      (parse-double (get row column))])
                                                   quantiles))))
                    forecasts)))