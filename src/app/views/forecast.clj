(ns app.views.forecast)

(defn row
  [{:forecast/keys [date seats demand-q50]}]
  [:tr {:class "border-b border-sky-100 last:border-0"}
   [:td {:class "py-3 pr-2 tabular-nums"} date]
   [:td {:class "py-3 px-1 text-right tabular-nums"} (str seats)]
   [:td {:class "py-3 pl-1 text-right tabular-nums"} (format "%.2f" demand-q50)]])
