(ns app.views.forecast)

(defn row
  [{:forecast/keys [date seats demand-q50]}]
  [:tr [:td date]
   [:td (str seats)]
   [:td (str demand-q50)]])
