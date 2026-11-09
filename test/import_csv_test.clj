(ns import-csv-test
  (:require [clojure.test :refer [deftest is]]
            [import-csv :as importer]))

(deftest transactions-share-cities-and-preserve-route-data
  (let [destinations [["AER" "Сочи"] ["LED" "Санкт-Петербург"]
                      ["KGD" "Калининград"] ["OVB" "Новосибирск"]
                      ["MRV" "Минеральные Воды"]]
        routes (mapv (fn [[code name]]
                       {"route" (str "MOW-" code)
                        "name" (str "Москва — " name)
                        "base_price" "9000" "cost_per_passenger" "4200"
                        "price_sensitivity" "0.141" "price_min" "5400"
                        "price_max" "18000"})
                     destinations)
        [cities route-tx] (importer/transactions routes [])
        imported-routes (filter :route/id route-tx)]
    (is (= (into {"MOW" "Москва"} destinations)
           (into {} (map (juxt :city/id :city/name)) cities)))
    (is (= (mapv (fn [[code _]]
                   [[:city/id "MOW"] [:city/id code]])
                 destinations)
           (mapv (juxt :route/origin-city :route/destination-city)
                 imported-routes)))))
