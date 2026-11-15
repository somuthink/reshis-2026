(ns app.views.fields
  (:require [clojure.string :as str]
            [com.wsscode.pathom3.connect.operation :as pco]
            [jsonista.core :as json]
            [scriptorium.pathom.ui :as ui]))

(defn- attributes [options attribute]
  (let [{:keys [name required? disabled? input-attrs type properties]} options]
    (merge input-attrs
           (cond-> {:class "field-control" :name name :required required? :disabled disabled?
                    (keyword (str "data-bind:" (clojure.core/name attribute))) true}
             (and (= :string type) (:min properties)) (assoc :minlength (:min properties))
             (and (= :string type) (:max properties)) (assoc :maxlength (:max properties))))))

(defmethod ui/render-field [:string :default]
  [_registry attribute value input field-options]
  (let [{:keys [options input-type disabled? label]} field-options
        control (if options
                  (let [choices (sort-by (or (:sort-by options) (:label options))
                                         (get input (:source options)))
                        signal (str "$" (name attribute))
                        values (str/replace (json/write-value-as-string (mapv (:value options) choices))
                                            #"[$@]" {"$" "\\u0024" "@" "\\u0040"})]
                    [:select (assoc (attributes field-options attribute)
                                    :disabled (or disabled? (empty? choices))
                                    :data-effect (str signal " = " values ".includes(" signal ") ? "
                                                      signal " : ''; el.value = " signal))
                     [:option {:value ""} "Выберите значение"]
                     (for [option choices]
                       [:option {:value ((:value options) option)
                                 :selected (= value ((:value options) option))}
                        ((:label options) option)])])
                  [:input (assoc (attributes field-options attribute)
                                 :type (or input-type "text") :value (or value ""))])]
    (if label
      [:label {:class "block font-medium text-slate-800"} label control]
      control)))

(defmethod ui/render-field [:string :password]
  [registry attribute _ input field-options]
  ((get-method ui/render-field [:string :default])
   registry attribute nil input field-options))

(defmethod ui/render-field [:vector :default]
  [_registry attribute value input {:keys [options name input-attrs]}]
  (let [choices (sort-by (:label options) (get input (:source options)))
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
  [registry attribute value input field-options]
  ((get-method ui/render-field [:string :default])
   registry attribute ((:value (:options field-options)) value) input field-options))

(defmethod ui/render-field [:double :default]
  [_registry attribute value _ {:keys [label] :as field-options}]
  (let [control [:input (assoc (attributes field-options attribute)
                               :type "number" :step "any" :value (or value "")
                               :data-signals__ifmissing (str "{" (name attribute) ": " (or value 0) "}")
                               :data-effect (str "el.setAttribute('value', $" (name attribute) ")"))]]
    (if label [:label {:class "block font-medium text-slate-800"} label control] control)))

(defmethod ui/render-field [:double :slider]
  [registry attribute value input {:keys [options] :as field-options}]
  (let [bounds (get input (:source options))
        minimum (get bounds (:min options))
        maximum (get bounds (:max options))
        signal (str "$" (name attribute))
        number-input ((get-method ui/render-field [:double :default])
                      registry attribute value input field-options)]
    [:div {:class "grid gap-1"}
     number-input
     [:input {:type "range" :min (or minimum 0) :max (or maximum 1) :step 1
              :disabled (nil? bounds) :aria-label "Ползунок"
              :class "price-slider"
              :data-effect (str "el.min = " (or minimum 0) "; el.max = " (or maximum 1)
                                "; el.value = " signal)
              :data-on:input (str signal " = Number(el.value)")}]
     (when bounds
       [:div {:class "mt-3 grid grid-cols-3 gap-2"}
        (for [[label price] [["Минимум" minimum]
                             ["Базовая" (get bounds (:base options))]
                             ["Максимум" maximum]]]
          [:button {:type "button"
                    :class "min-w-0 rounded-xl border border-sky-200 px-2 py-3 text-sky-950 hover:bg-sky-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-600 aria-pressed:border-sky-700 aria-pressed:bg-sky-50"
                    :data-attr:aria-pressed (str signal " === " price " ? 'true' : 'false'")
                    :data-on:click (str signal " = " price "; el.dispatchEvent(new Event('input', {bubbles: true}))")}
           [:span {:class "block text-sm text-slate-600"} label]
           [:span {:class "mt-1 block text-xl font-semibold tabular-nums"} (format "%.0f" price)]])])]))

(defmethod ui/render-field [:time/local-date :date]
  [_registry attribute value input {:keys [options disabled? label] :as field-options}]
  (let [dates (->> (get input (:source options)) (map (:value options)) (map str) distinct sort vec)
        values (str/replace (json/write-value-as-string dates)
                            #"[$@]" {"$" "\\u0024" "@" "\\u0040"})
        signal (str "$" (name attribute))
        index (str values ".indexOf(" signal ")")
        button (fn [step aria-label glyph boundary]
                 [:button {:type "button" :aria-label aria-label :disabled true
                           :class "field-control" :style "width:2.5rem;flex:0 0 2.5rem;padding:0"
                           :data-attr:disabled (str index " < 0 || " index boundary)
                           :data-on:click (str signal " = " values "[" index " + (" step ")]; el.dispatchEvent(new Event('input', {bubbles: true}))")}
                  glyph])
        control [:input (assoc (attributes field-options attribute)
                               :type "date" :value (some-> value str)
                               :min (first dates) :max (peek dates)
                               :disabled (or disabled? (empty? dates))
                               :data-init (str "if (!" values ".includes(" signal ")) " signal " = ''")
                               :data-effect (str "el.value = " signal "; el.setCustomValidity(!" signal " || "
                                                 values ".includes(" signal ") ? '' : 'Для этой даты нет прогноза')")
                               :data-on:change "el.reportValidity()")]]
    [:div {:class "flex min-w-0 items-center gap-2"}
     (button -1 "Предыдущая доступная дата" "‹" " <= 0")
     (if label
       [:label {:class "grid gap-1 font-medium text-slate-800"} label control]
       control)
     (button 1 "Следующая доступная дата" "›" (str " >= " (dec (count dates))))]))

(defn date-field
  [env {:scriptorium/keys [registry] :scenario/keys [date] :as input}]
  (ui/render-field registry :scenario/date date input
                   (cond-> (ui/field-options registry :scenario/date date)
                     (false? (:label? (pco/params env))) (assoc :label nil))))
