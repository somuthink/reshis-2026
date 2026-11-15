(ns app.views.scenario-test
  (:require [app.views.scenario :as scenario]
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
                points (map second (filter #(= :circle (first %)) nodes))
                capacity (->> nodes
                              (filter #(and (= :line (first %))
                                            (= "capacity" (:class (second %)))))
                              (map second))]]
    (testing (str "Demand quantiles: " demands)
      (is (= 9 (count points)))
      (is (apply <= (map :cx points)))
      (is (apply < (map :cy points)))
      (is (every? #(Double/isFinite (double %)) (mapcat (juxt :cx :cy) points)))
      (is (= 2 (count capacity)))
      (is (apply = (map :x1 capacity)))
      (is (= (mapv #(compare % seats) demands)
             (mapv #(compare (:cx %) (:x1 (first capacity))) points)))
      (when (apply = demands)
        (is (apply = (map :cx points)))))))
