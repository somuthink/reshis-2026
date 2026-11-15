(ns app.views.scenario-test
  (:require [app.views.scenario :as scenario]
            [jsonista.core :as json]
            [clojure.test :refer [deftest is testing]]))

(deftest demand-and-capacity-use-the-same-scale
  (doseq [[demands seats] [[[10.0 20.0 30.0 40.0 50.0 60.0 70.0 80.0 90.0] 50]
                           [[-90.0 -80.0 -70.0 -60.0 -50.0 -40.0 -30.0 -20.0 -10.0] 0]
                           [(vec (repeat 9 0.0)) 0]]
          :let [chart (scenario/chart
                       {:scenario/estimate {:seats seats}
                        :scenario/distribution (mapv (fn [i demand]
                                                       {:quantile (/ i 10.0) :demand demand})
                                                     (range 1 10) demands)})
                nodes (filter vector? (tree-seq coll? seq chart))
                component (second (first (filter #(= :exceedance-chart (first %)) nodes)))
                [minimum maximum] (json/read-value (:domain component))
                project (fn [demand] (+ 60 (* 280 (/ (- demand minimum) (- maximum minimum)))))
                markers (map second (filter #(and (= :line (first %)) (= 55 (:y1 (second %)))) nodes))
                capacity (second (first (filter #(and (= :line (first %))
                                                      (= "capacity" (:class (second %)))) nodes)))]]
    (testing (str "Shared server SVG / ECharts projection: " demands)
      (is (< minimum maximum))
      (is (= (count demands) (count markers)))
      (is (every? #(Double/isFinite (double %)) [minimum maximum (project seats)]))
      (is (every? true? (map (fn [demand marker] (< (Math/abs (- (project demand) (:x1 marker))) 1e-8))
                             demands markers)))
      (is (< (Math/abs (- (project seats) (:x1 capacity))) 1e-8)))))
