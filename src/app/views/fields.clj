(ns app.views.fields
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [scriptorium.pathom.ui :as ui]
            [scriptorium.registry :as registry]))

(defn- attributes [registry attribute]
  (let [{:keys [name required? disabled? input-attrs]} (ui/field-options registry attribute nil)]
    (merge input-attrs
           {:class "field-control" :name name :required required? :disabled disabled?
            (keyword (str "data-bind:" (clojure.core/name attribute))) true})))

(defn- input-id [registry attribute input]
  (str (ui/view-id (keyword (namespace attribute) (str ">" (name attribute)))
                   (get input (:identity-key (registry/identify-entity registry input))))
       "-input"))

(defmethod ui/render-field [:string :default]
  [registry attribute value input]
  (let [{:keys [options input-type disabled?]} (ui/field-options registry attribute value)]
    (if options
      (let [choices (sort-by (or (:sort-by options) (:label options))
                             (get input (:source options)))
            signal (str "$" (name attribute))
            values (str/replace (json/write-value-as-string (mapv (:value options) choices))
                                #"[$@]" {"$" "\\u0024" "@" "\\u0040"})]
        [:select (assoc (attributes registry attribute)
                        :disabled (or disabled? (empty? choices))
                        :data-effect (str signal " = " values ".includes(" signal ") ? "
                                          signal " : ''; el.value = " signal))
         [:option {:value ""} "Выберите значение"]
         (for [option choices]
           [:option {:value ((:value options) option)
                     :selected (= value ((:value options) option))}
            ((:label options) option)])])
      [:input (assoc (attributes registry attribute) :type (or input-type "text") :value (or value ""))])))

(defmethod ui/render-field [:string :password]
  [registry attribute _ input]
  ((get-method ui/render-field [:string :default]) registry attribute nil input))

(defmethod ui/render-field [:vector :default]
  [registry attribute value input]
  (let [{:keys [options name input-attrs]} (ui/field-options registry attribute value)
        choices (sort-by (:label options) (get input (:source options)))
        selected (if (and (nil? value) (:default-all? options)) choices value)
        selected-ids (mapv (:value options) selected)
        selected? (set selected-ids)
        signal (str "$" (clojure.core/name attribute))]
    [:div {:class "mt-3 grid gap-3 sm:grid-cols-2"
           :data-signals (str/replace
                          (json/write-value-as-string {(keyword (clojure.core/name attribute)) selected-ids})
                          #"[$@]" {"$" "\\u0024" "@" "\\u0040"})}
     (for [choice choices
           :let [id ((:value options) choice)]]
       [:label {:class "flex items-center gap-3 text-slate-800"}
        [:input (merge input-attrs
                       {:type "checkbox" :name name :value id :checked (contains? selected? id)
                        :class "h-5 w-5 accent-sky-600"
                        :data-on:change (str signal " = Array.from(el.form.querySelectorAll("
                                             (json/write-value-as-string (str "input[name=\"" name "\"]:checked"))
                                             "), input => input.value)")})]
        [:span ((:label options) choice)]])]))

(defmethod ui/render-field [:s/ref :default]
  [registry attribute value input]
  (let [{:keys [options]} (ui/field-options registry attribute value)]
    ((get-method ui/render-field [:string :default])
     registry attribute ((:value options) value) input)))

(defmethod ui/render-field [:double :default]
  [registry attribute value _]
  [:input (assoc (attributes registry attribute) :type "number" :step "any" :value (or value ""))])

(defmethod ui/render-field [:double :slider]
  [registry attribute value input]
  (let [{:keys [options]} (ui/field-options registry attribute value)
        bounds (get input (:source options))
        minimum (get bounds (:min options))
        maximum (get bounds (:max options))
        signal (str "$" (name attribute))
        input-id (input-id registry attribute input)
        number-input ((get-method ui/render-field [:double :default]) registry attribute value input)]
    [:div {:class "grid gap-1"}
     (assoc-in number-input [1 :id] input-id)
     [:input {:type "range" :min (or minimum 0) :max (or maximum 1) :step 1
              :disabled (nil? bounds) :aria-label "Ползунок"
              :class "price-slider"
              :data-effect (str "el.value = " signal)
              :data-on:input (str signal " = Number(el.value)")}]
     (when bounds
       [:div {:class "flex justify-between text-sm text-slate-500 tabular-nums"}
        [:span (format "%.0f" minimum)]
        [:span (format "%.0f" maximum)]])]))

(defmethod ui/render-field [:time/local-date :date]
  [registry attribute value input]
  (let [{:keys [options disabled?]} (ui/field-options registry attribute value)
        dates (->> (get input (:source options)) (map (:value options)) (map str) distinct sort vec)
        values (str/replace (json/write-value-as-string dates)
                            #"[$@]" {"$" "\\u0024" "@" "\\u0040"})
        signal (str "$" (name attribute))
        index (str values ".indexOf(" signal ")")
        button (fn [step label glyph boundary]
                 [:button {:type "button" :aria-label label :disabled true
                           :class "field-control" :style "width:2.5rem;flex:0 0 2.5rem;padding:0"
                           :data-attr:disabled (str index " < 0 || " index boundary)
                           :data-on:click (str signal " = " values "[" index " + (" step ")]")}
                  glyph])]
    [:div {:class "flex min-w-0 items-center gap-2"}
     (button -1 "Предыдущая доступная дата" "‹" " <= 0")
     [:input (assoc (attributes registry attribute)
                    :id (input-id registry attribute input)
                    :type "date" :value (some-> value str)
                    :min (first dates) :max (peek dates)
                    :disabled (or disabled? (empty? dates))
                    :data-init (str "if (!" values ".includes(" signal ")) " signal " = ''")
                    :data-effect (str "el.value = " signal "; el.setCustomValidity(!" signal " || "
                                      values ".includes(" signal ") ? '' : 'Для этой даты нет прогноза')")
                    :data-on:change "el.reportValidity()")]
     (button 1 "Следующая доступная дата" "›" (str " >= " (dec (count dates))))]))
