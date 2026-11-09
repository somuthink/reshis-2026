(ns app.views.fields
  (:require [scriptorium.pathom.ui :as ui]))

(defn- attributes [registry attribute]
  (let [{:keys [name required? disabled?]} (ui/field-options registry attribute nil)]
    {:class "field-control" :name name :required required? :disabled disabled?
     (keyword (str "data-bind:" (clojure.core/name attribute))) true}))

(defmethod ui/render-field [:string :default]
  [registry attribute value input]
  (let [{:keys [options input-type]} (ui/field-options registry attribute value)]
    (if options
      [:select (attributes registry attribute)
       [:option {:value ""} "Выберите значение"]
       (for [option (sort-by (:label options) (get input (:source options)))]
         [:option {:value ((:value options) option)
                   :selected (= value ((:value options) option))}
          ((:label options) option)])]
      [:input (assoc (attributes registry attribute) :type (or input-type "text") :value (or value ""))])))

(defmethod ui/render-field [:s/ref :default]
  [registry attribute value input]
  (let [{:keys [options]} (ui/field-options registry attribute value)]
    ((get-method ui/render-field [:string :default])
     registry attribute ((:value options) value) input)))

(defmethod ui/render-field [:double :default]
  [registry attribute value _]
  [:input (assoc (attributes registry attribute) :type "number" :step "any" :value (or value ""))])
