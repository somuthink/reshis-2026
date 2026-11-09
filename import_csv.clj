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

(defn forecast [row]
  (merge {:forecast/id (random-uuid)
          :forecast/date (get row "date")
          :forecast/seats (parse-long (get row "seats"))}
         (into {} (map-indexed (fn [i column]
                                 [(keyword "forecast" (str "demand-q" (* 10 (inc i))))
                                  (parse-double (get row column))])
                               quantiles))))

(defn transactions [routes forecast-rows]
  (let [cities (into {}
                     (mapcat (fn [{:strs [route name]}]
                               (map vector
                                    (str/split route #"-" 2)
                                    (str/split name #"\s*—\s*" 2)))
                             routes))
        forecasts (group-by #(get % "route") forecast-rows)]
    [(mapv (fn [[code name]] {:city/id code :city/name name}) cities)
     (mapv (fn [{:strs [route name base_price cost_per_passenger price_sensitivity price_min price_max]}]
             (let [[origin destination] (str/split route #"-" 2)]
               {:route/id route :route/name name
                :route/origin-city [:city/id origin]
                :route/destination-city [:city/id destination]
                :route/base-price (parse-double base_price)
                :route/cost-per-passenger (parse-double cost_per_passenger)
                :route/price-sensitivity (parse-double price_sensitivity)
                :route/price-min (parse-double price_min)
                :route/price-max (parse-double price_max)
                :route/forecasts (mapv forecast (get forecasts route))}))
           routes)]))

(defn -main [& [routes-source forecasts-source]]
  (let [routes (rows (or routes-source
                         "https://gist.githubusercontent.com/Murkat-git/3ddc868889d3e0d37542d786c8b1aa74/raw/a934b1220da2b20d7f3eb397a7459fe78ea168ef/routes.csv"))
        forecasts (rows (or forecasts-source
                            "https://gist.githubusercontent.com/Murkat-git/3ddc868889d3e0d37542d786c8b1aa74/raw/seasonal_forecast.csv"))
        conn (d/connect {:store {:backend :file
                                 :path "data/routes-db"
                                 :id #uuid "f67b64bd-57bb-49fb-a998-3b28530bb800"}
                         :schema-flexibility :write})]
    (doseq [tx (transactions routes forecasts)]
      (d/transact conn tx))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
