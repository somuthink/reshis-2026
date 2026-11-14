(ns app.views.user
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [scriptorium.datastar :as ds]
            [scriptorium.pathom.ui :as ui]))

(defn result [{:user/keys [authenticated? errors]}]
  (let [destination (when-not (seq errors) (if authenticated? "/" "/login"))]
    [:div (cond-> {:id "auth-result" :role "status" :aria-live "polite"}
            destination (assoc :data-init (str "window.location.assign('" destination "')"))
            (seq errors) (assoc :data-init "$password = ''; document.querySelectorAll('input[type=password]').forEach(input => input.value = '')"))
     (when (seq errors)
       [:ul {:role "alert" :class "mt-5 rounded-xl border border-red-300 bg-red-50 p-4 text-red-900"}
        (for [error errors] [:li error])])
     (when destination "Переход…")]))

(defn- panel [email title & content]
  [:main {:class "mx-auto w-full max-w-xl px-4 py-12 sm:px-8"
          :data-signals (str/replace
                         (json/write-value-as-string {:email email :password ""})
                         #"[$@]" {"$" "\\u0024" "@" "\\u0040"})}
   [:section {:class "clay-panel p-6 sm:p-8"}
    [:h1 {:class "text-2xl font-medium text-slate-900"} title]
    content]])

(defn- field [id attribute label view]
  [:div
   [:label {:for (ui/view-id attribute id) :class "block font-medium text-slate-800"} label]
   view])

(defn- submit [label]
  [:button {:type "submit" :class "w-full rounded-xl bg-sky-700 px-5 py-3 font-medium text-white hover:bg-sky-800 focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-sky-600"}
   label])

(defn login-form [{:user/keys [id email >email >password]}]
  (panel email "Вход"
         [:div {:id "auth-result" :role "status" :aria-live "polite"}]
         [:form (merge {:method "post" :action "/eql" :class "mt-6 space-y-5"}
                       (ds/on :submit
                              [{(list 'app.mutations.user/login {:email '$email :password '$password})
                                [:user/>auth-result]}] {}))
          (field id :user/>email "Электронная почта" >email)
          (field id :user/>password "Пароль" >password)
          (submit "Войти")]
         [:p {:class "mt-6 text-center text-slate-700"} "Нет аккаунта? "
          [:a {:href "/register" :class "text-sky-800 underline underline-offset-4"} "Зарегистрироваться"]]))

(defn register-form [{:user/keys [id email >email >password >cities]}]
  (panel email "Регистрация"
         [:div {:id "auth-result" :role "status" :aria-live "polite"}]
         [:form (merge {:method "post" :action "/eql" :class "mt-6 space-y-5"}
                       (ds/on :submit
                              [{(list 'app.mutations.user/register
                                      {:email '$email :password '$password :cities '$cities})
                                [:user/>auth-result]}] {}))
          (field id :user/>email "Электронная почта" >email)
          (field id :user/>password "Пароль"
                 (update >password 1 assoc :autocomplete "new-password"
                         :minlength 12 :aria-describedby "password-help"))
          [:p {:id "password-help" :class "mt-2 text-sm text-slate-600"} "От 12 до 256 символов."]
          [:fieldset
           [:legend {:class "font-medium text-slate-800"} "Доступные города"]
           [:p {:id "cities-help" :class "mt-2 text-sm text-slate-600"}
            "Демонстрационный режим: вы сами выбираете права доступа. Доступны только маршруты, оба города которых отмечены."]
           >cities]
          (submit "Зарегистрироваться")]
         [:p {:class "mt-6 text-center text-slate-700"} "Уже есть аккаунт? "
          [:a {:href "/login" :class "text-sky-800 underline underline-offset-4"} "Войти"]]))

(defn form [{:user/keys [id email >email]}]
  (panel email "Аккаунт"
         [:fieldset {:disabled true :class "mt-6"}
          (field id :user/>email "Электронная почта" >email)]
         [:p {:class "mt-6 text-center text-slate-700"}
          [:a {:href "/" :class "text-sky-800 underline underline-offset-4"} "Перейти к расчёту"]]))
