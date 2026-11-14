# Решись Авиа

Веб-приложение для оценки спроса и продаж на авиарейсах. Пользователь выбирает маршрут, дату и цену; сервер возвращает прогноз продаж и распределение спроса

## Архитектура

В приложении [Scriptorium](https://github.com/somuthink/scriptorium) связывает схемы данных, Pathom-резолверы, Datahike и HTTP. Malli-схема задаёт типы и связи; EQL-запрос описывает нужные ветви графа.

- **[Clojure](https://clojure.org/)** - функциональный Lisp для JVM с неизменяемыми persistent-структурами и REPL.
- **[Malli](https://github.com/metosin/malli)** - схемы как данные Clojure; поддерживает композицию, расширение через registry и пользовательские схемы. Scriptorium собирает их в общий registry и использует для Datahike, Pathom и полей форм.
- **[Pathom 3](https://github.com/wilkerlucio/pathom3) и [EQL](https://github.com/edn-query-language/eql)** - EQL-запросы задают нужные атрибуты и join-ы как EDN. Резолверы объявляют входы и выходы; Pathom строит план вычислений для запрошенного графа. Запросы можно собирать и преобразовывать средствами Clojure; отдельная GraphQL SDL-схема не нужна, но клиенту нужен контракт EQL-атрибутов.
- **[Datahike](https://github.com/replikativ/datahike) и [Datalog](https://en.wikipedia.org/wiki/Datalog)** - база фактов с декларативными запросами: общие переменные связывают условия `:where`, поэтому запрос описывает нужные связи, а не ручной порядок обхода. Приложение ищет маршруты по городам и прогнозы по маршруту и дате. Datahike хранит историю транзакций и поддерживает ветки; здесь используется файловая база, пользовательские ветки не задействованы.
- **[EACL](https://github.com/theronic/eacl)** - ReBAC-модель доступа, вдохновлённая [SpiceDB](https://github.com/authzed/spicedb): отношения «субъект - право - ресурс» хранятся в Datahike, а Pathom проверяет их при выполнении операций. Доступ по умолчанию закрыт.
- **[Hiccup](https://github.com/weavejester/hiccup) и [Chassis](https://github.com/onionpancakes/chassis)** - view-функции возвращают HTML-дерево в виде Clojure-векторов и карт; Chassis сериализует его на сервере.
- **[Datastar](https://data-star.dev/)** - браузер отправляет изменения формы на `/eql`; сервер возвращает SSE с HTML-патчем, который Datastar применяет к DOM. Формы и вычисления остаются на сервере без отдельного SPA-слоя.
- **[Reitit](https://github.com/metosin/reitit) и [Ring](https://github.com/ring-clojure/ring)** - маршруты объявлены данными; Reitit собирает Ring-роутер и подключает EQL endpoint и middleware.
- **[Integrant](https://github.com/weavejester/integrant)** - запускает и останавливает компоненты приложения в заданном порядке.
- **[Babashka](https://babashka.org/) и [`clojure.data.csv`](https://github.com/clojure/data.csv)** - запускают импорт CSV из командной строки и разбирают строки файлов.

## Scriptorium в этом приложении

Malli-описания EDN собираются в registry; Scriptorium компилирует его в схемы сущностей и операции Pathom. При запросе Pathom проходит от требуемого view к его зависимостям по `:in`, раскрывает ссылки на сущности и получает запрошенные атрибуты. Для полей `:>in` задаёт данные, которые приложение передаёт renderer-у; приложение само трактует `:>options` и формирует варианты. Scriptorium не обрабатывает picker-опции.

### Форма и расчёт сценария

Запрос страницы требует `:scenario/>form`. Его `:in` перечисляет четыре HTML-поля. Например, поле «Откуда» объявляет источник вариантов через `:>in`, а Pathom-резолвер `:scenario/route` объявляет выбранные идентификаторы городов:

```clojure
[:scenario/from
 {:>label "Откуда"
  :>in [{:route/origin-cities [:city/id :city/name]}]
  :>options {:source :route/origin-cities
             :value :city/id
             :label :city/name}}
 [:s/ref :city/id]]

[:scenario/route
 {:resolve :app.resolvers.scenario/route-selection
  :in [{:scenario/from [:city/id]}
       {:scenario/to [:city/id]}]}
 [:s/ref :route/id]]

[:scenario/>form
 {:resolve :app.views.scenario/form
  :in [:scenario/>from :scenario/>to
       :scenario/>date :scenario/>price]}
 :hiccup]
```

Стрелки показывают поток данных: источник передаёт атрибут резолверу или view. Верхняя часть графа раскрывает параметры формы, нижняя - значения после изменения пользователем и все атрибуты, запрашиваемые вычислениями и `:scenario/>result`.

```mermaid
flowchart LR
  TO[":scenario/to - выбранный :city/id"] --> ORIGIN_CITIES[":route/origin-cities, вход :to"]
  ORIGIN_CITIES --> ORIGIN_IDS["резолвер выдаёт :city/id"]
  ORIGIN_IDS --> ORIGIN_NAME["Datahike читает :city/name"]
  ORIGIN_IDS --> FROM_VIEW[":scenario/>from"]
  ORIGIN_NAME --> FROM_VIEW
  FROM[":scenario/from - выбранный :city/id"] --> DEST_CITIES[":route/destination-cities, вход :from"]
  DEST_CITIES --> DEST_IDS["резолвер выдаёт :city/id"]
  DEST_IDS --> DEST_NAME["Datahike читает :city/name"]
  DEST_IDS --> TO_VIEW[":scenario/>to"]
  DEST_NAME --> TO_VIEW
  FROM --> DATES[":route/forecast-dates резолвер"]
  TO --> DATES
  DATES --> DATE_OPTIONS[":route/forecast-dates : [:forecast/date]"]
  DATE_OPTIONS --> DATE_VIEW[":scenario/>date"]
  DATES --> SELECTED_ROUTE[":route/selected : [:route/id]"]
  SELECTED_ROUTE --> SELECTED_ID[":route/id"]
  SELECTED_ID --> PRICE_MIN[":route/price-min"]
  SELECTED_ID --> PRICE_MAX[":route/price-max"]
  PRICE_MIN --> PRICE_VIEW[":scenario/>price"]
  PRICE_MAX --> PRICE_VIEW
  FROM_VIEW --> FORM[":scenario/>form"]
  TO_VIEW --> FORM
  DATE_VIEW --> FORM
  PRICE_VIEW --> FORM

  FORM -.->|пользователь меняет поля| VALUES["значения формы"]
  VALUES --> FROM
  VALUES --> TO
  VALUES --> DATE[":scenario/date"]
  VALUES --> PRICE[":scenario/price"]
  FROM --> ROUTE[":scenario/route"]
  TO --> ROUTE
  ROUTE --> ROUTE_ID_FOR_FORECAST[":scenario/route join: :route/id"]
  ROUTE_ID_FOR_FORECAST --> FORECAST[":scenario/forecast"]
  DATE --> FORECAST
  ROUTE --> EST_ROUTE[":scenario/route join: :route/id, :route/name, :route/base-price, :route/price-sensitivity"]
  FORECAST --> EST_FORECAST[":scenario/forecast join: :forecast/id, :forecast/seats, :forecast/demand-q50"]
  FROM --> EST_FROM[":scenario/from join: :city/id"]
  TO --> EST_TO[":scenario/to join: :city/id"]
  EST_FROM --> ESTIMATE[":scenario/estimate"]
  EST_TO --> ESTIMATE
  DATE --> ESTIMATE
  PRICE --> ESTIMATE
  EST_ROUTE --> ESTIMATE
  EST_FORECAST --> ESTIMATE
  ROUTE --> DIST_ROUTE[":scenario/route join: :route/id, :route/base-price, :route/price-sensitivity"]
  FORECAST --> DIST_FORECAST[":scenario/forecast join: :forecast/id, :forecast/demand-q10, :forecast/demand-q20, :forecast/demand-q30, :forecast/demand-q40, :forecast/demand-q50, :forecast/demand-q60, :forecast/demand-q70, :forecast/demand-q80, :forecast/demand-q90"]
  DIST_ROUTE --> DISTRIBUTION[":scenario/distribution"]
  DIST_FORECAST --> DISTRIBUTION
  PRICE --> DISTRIBUTION
  ESTIMATE --> RESULT[":scenario/>result"]
  DISTRIBUTION --> RESULT
  ROUTE --> RESULT_ROUTE[":scenario/route :route/id; {:route/origin-city [:city/>link]}; {:route/destination-city [:city/>link]}"]
  RESULT_ROUTE --> CITY_LINKS[":city/>link for origin and destination"]
  CITY_LINKS --> RESULT
```

`:scenario/estimate` получает вложенные `{:scenario/from [:city/id]}` и `{:scenario/to [:city/id]}`. Join `:scenario/route` раскрывается до `:route/id`, `:route/name`, `:route/base-price` и `:route/price-sensitivity`; join `:scenario/forecast` - до `:forecast/id`, `:forecast/seats` и `:forecast/demand-q50`. `:scenario/distribution` запрашивает у join маршрута ID, базовую цену и чувствительность, а у join прогноза - все девять квантилей спроса. `:scenario/>result` соединяет оба расчёта с join маршрута, который раскрывает ссылки на города. Следующий граф показывает, из каких атрибутов собирается каждая ссылка.

### Вложенные страницы маршрута и города

Обработчик маршрута передаёт в Pathom ID из URL и запрашивает `:route/>view`; обработчик города так же запрашивает `:city/>view` по `:city/id`. В `:in` этих view перечислены прямые поля и вложенные join-ы; ниже они показаны до атрибутов, из которых строится каждый Hiccup-узел:

```clojure
[:route/>view
 {:resolve :app.views.route/detail
  :in [:route/id :route/name :route/base-price
       :route/cost-per-passenger :route/price-sensitivity
       :route/price-min :route/price-max
       {:route/origin-city [:city/>link]}
       {:route/destination-city [:city/>link]}
       {:route/forecasts [:forecast/date :forecast/>row]}]}
 :hiccup]

[:city/>link
 {:resolve :app.views.city/link
  :in [:city/id :city/name]}
 :hiccup]

[:route/>link
 {:resolve :app.views.route/link
  :in [:route/id :route/name]}
 :hiccup]

[:forecast/>row
 {:resolve :app.views.forecast/row
  :in [:forecast/date :forecast/seats :forecast/demand-q50]}
 :hiccup]

[:city/>view
 {:resolve :app.views.city/detail
  :in [:city/id :city/name
       {:city/outgoing-routes [:route/id :route/name :route/>link]}
       {:city/incoming-routes [:route/id :route/name :route/>link]}]}
 :hiccup]

[:city/outgoing-routes
 {:resolve :app.resolvers.city/outgoing-routes
  :in [:city/id]}
 [:vector [:s/ref :route/id]]]

[:city/incoming-routes
 {:resolve :app.resolvers.city/incoming-routes
  :in [:city/id]}
 [:vector [:s/ref :route/id]]]
```

В `:route/>view` семь прямых route-полей. Join-ы `:route/origin-city` и `:route/destination-city` следуют по ссылкам `[:s/ref :city/id]`, затем запрашивают `:city/>link`; его `:in` приводит к `:city/id` и `:city/name`. Join `:route/forecasts` проходит по вектору `[:s/ref :forecast/id]`; из каждой forecast-сущности читается `:forecast/date`, а `:forecast/>row` разворачивается в дату, места и медианный спрос.

`:city/>view` запрашивает ID и имя города, а также два маршрутных join-а. Резолверы `:city/outgoing-routes` и `:city/incoming-routes` принимают `:city/id` и возвращают route ID. Каждый результат join раскрывает в `:route/id`, `:route/name` и `:route/>link`; последний строит HTML-ссылку из ID и имени. В графе показаны оба пути до Hiccup-view:

```mermaid
flowchart LR
  subgraph ROUTE_PAGE["Страница маршрута: :route/>view"]
    R_ID[":route/id"] --> RV[":route/>view"]
    R_NAME[":route/name"] --> RV
    BASE[":route/base-price"] --> RV
    COST[":route/cost-per-passenger"] --> RV
    SENS[":route/price-sensitivity"] --> RV
    MIN[":route/price-min"] --> RV
    MAX[":route/price-max"] --> RV

    ORIGIN_REF[":route/origin-city : [:s/ref :city/id]"] --> ORIGIN_CITY["сущность города по ID"]
    ORIGIN_CITY --> O_ID[":city/id"]
    ORIGIN_CITY --> O_NAME[":city/name"]
    O_ID --> O_LINK[":city/>link"]
    O_NAME --> O_LINK
    O_LINK --> RV

    DEST_REF[":route/destination-city : [:s/ref :city/id]"] --> DEST_CITY["сущность города по ID"]
    DEST_CITY --> D_ID[":city/id"]
    DEST_CITY --> D_NAME[":city/name"]
    D_ID --> D_LINK[":city/>link"]
    D_NAME --> D_LINK
    D_LINK --> RV

    FORECAST_REFS[":route/forecasts : [:vector [:s/ref :forecast/id]]"] --> FORECAST["сущность прогноза по ID"]
    FORECAST --> F_DATE[":forecast/date"]
    FORECAST --> F_SEATS[":forecast/seats"]
    FORECAST --> Q50[":forecast/demand-q50"]
    F_DATE --> F_ROW[":forecast/>row"]
    F_SEATS --> F_ROW
    Q50 --> F_ROW
    F_DATE --> RV
    F_ROW --> RV
  end

  subgraph CITY_PAGE["Страница города: :city/>view"]
    C_ID[":city/id"] --> CV[":city/>view"]
    C_NAME[":city/name"] --> CV
    C_ID --> OUT[":city/outgoing-routes резолвер"]
    C_ID --> IN[":city/incoming-routes резолвер"]
    OUT --> OUT_REFS["вектор ссылок :route/id"]
    IN --> IN_REFS["вектор ссылок :route/id"]
    OUT_REFS --> OUT_ROUTE["сущность маршрута по ID"]
    IN_REFS --> IN_ROUTE["сущность маршрута по ID"]
    OUT_ROUTE --> OUT_ID[":route/id"]
    OUT_ROUTE --> OUT_NAME[":route/name"]
    IN_ROUTE --> IN_ID[":route/id"]
    IN_ROUTE --> IN_NAME[":route/name"]
    OUT_ID --> OUT_LINK[":route/>link"]
    OUT_NAME --> OUT_LINK
    IN_ID --> IN_LINK[":route/>link"]
    IN_NAME --> IN_LINK
    OUT_LINK --> CV
    IN_LINK --> CV
  end
```

Резолверы прилётов и вылетов фильтруют маршруты по ID города и правам EACL. Остальные листья, например `:city/name` или `:route/name`, Pathom раскрывает по соответствующим схемам и доступным resolver-ам; view-функции получают уже собранные вложенные значения и возвращают Hiccup.

## Что умеет приложение

- Выбор городов, даты прогноза и цены. Список доступных дат зависит от выбранного маршрута.
- Расчёт спроса по медианному прогнозу и ценовой чувствительности:

  ```text
  спрос = медианный спрос − чувствительность к цене × (цена − базовая цена)
  продажи = min(места, max(0, спрос))
  ```

- Числовое поле цены и ползунок с границами тарифа; изменение формы пересчитывает результат с задержкой 300 мс.
- График девяти квантилей спроса. Медиана совпадает с расчётом продаж, вместимость отмечена отдельно; таблица значений доступна скринридерам.
- Регистрация, вход и права просмотра маршрутов. Пароли хранятся в виде PBKDF2-SHA256-хэшей; пользователь видит только разрешённые маршруты и связанные с ними данные.

## Запуск и импорт данных

### Запуск приложения

Из корня проекта запусти:

```sh
clojure -M:dev
```

В открывшемся nREPL выполни:

```clojure
(require 'user)
(integrant.repl/go)
```

Приложение создаст схему, откроет `data/routes-db` и будет доступно на порту `3000`.

### Запуск без REPL

Для запуска HTTP-сервера напрямую, без nREPL:

```sh
DATABASE_PATH=/path/to/routes-db PORT=3000 clojure -M -m app.main
```

`DATABASE_PATH` и `PORT` можно не задавать: по умолчанию используются `data/routes-db` и порт `3000`.

`IP` задаёт адрес HTTP-сервера (по умолчанию `0.0.0.0`). В Alwaysdata выбери тип сайта **User program**: приложение использует собственный HTTP-сервер, не Tomcat. Платформа передаёт `IP` и `PORT`; не переопределяй их.

### Render / Docker

Dockerfile собирает JAR вместе с закреплённой версией Scriptorium. В Render выбери Docker, ветку `main`, путь `./Dockerfile`, контекст и корневую директорию оставь по умолчанию, health check — `/login`. Docker Command и Pre-Deploy Command не нужны. Render передаёт `PORT`; образ слушает `0.0.0.0`.

Локальная проверка: `docker build -t reshis .`, затем `docker run --rm -p 10000:10000 reshis`. Открой `http://localhost:10000/login`.

При сборке приложение создаёт схему пустой базы, затем `bb import_csv.clj` импортирует города, маршруты и прогнозы. Прогнозы берутся из `deploy/my_forecast.csv` (копия `../ml/my_forecast.csv`), маршруты — по URL из `import.edn`. Готовая база включается в образ без локальных пользователей и сценариев.

Render Free не поддерживает постоянные диски: регистрации и другие изменения теряются при перезапуске, развёртывании или засыпании после 15 минут без запросов. После запуска снова доступны исходные импортированные данные.

### Импорт CSV

Настройки по умолчанию лежат в [`import.edn`](import.edn): маршруты загружаются по указанному URL, прогнозы - из `../ml/my_forecast.csv`. Оба пути разрешаются относительно текущего рабочего каталога, поэтому запускай команду из корня приложения.

Останови приложение в nREPL перед импортом, чтобы освободить файловую базу:

```clojure
(integrant.repl/halt)
```

Затем запусти импорт с настройками из `import.edn`:

```sh
bb import_csv.clj
```

Скрипт также принимает два позиционных аргумента - CSV маршрутов и CSV прогнозов. Они заменяют соответствующие значения из `import.edn`:

```sh
bb import_csv.clj routes.csv forecast.csv
```

После импорта снова выполни `(integrant.repl/go)` в nREPL. Импорт добавляет записи прогнозов; он не заменяет ранее загруженные прогнозы.

Для собственных файлов измени `:routes-source` и `:forecasts-source` в `import.edn` либо передай оба пути аргументами. Например, `../ml/my_forecast.csv` из текущего `import.edn` - относительный путь к CSV, а не встроенная в проект копия файла.

## Проверки и сборка

```sh
clojure -M:test -e "(require 'clojure.test 'app.user-test 'app.mutations.user-test 'app.acl-test 'app.resolvers.scenario-test) (let [r (clojure.test/run-tests 'app.user-test 'app.mutations.user-test 'app.acl-test 'app.resolvers.scenario-test)] (System/exit (+ (:fail r) (:error r))))"
bb -cp .:test -e "(require 'import-csv-test) (let [r (clojure.test/run-tests 'import-csv-test)] (System/exit (+ (:fail r) (:error r))))"
```

Собрать JAR: `clojure -T:build uber`. Для запуска укажи `DATABASE_PATH` и `PORT`; перед переносом базы останови приложение и скопируй `data/routes-db`.

## Инструменты разработки

- **Редакторы кода**
  - [Sublime Text](https://www.sublimetext.com/) с [Clojure Sublimed](https://github.com/tonsky/Clojure-Sublimed).
  - [Neovim](https://neovim.io/) с [Conjure](https://github.com/Olical/conjure).
- **[Oh My Pi (OMP)](https://github.com/can1357/oh-my-pi)** - harness, который связывает модели, инструменты и рабочий контекст.
  - [brepl](https://github.com/licht1stein/brepl) - bracket-fixing agent and CLI-клиент для подключения к Clojure [nREPL](https://github.com/nrepl/nrepl).
  - [ast-grep](https://github.com/ast-grep/ast-grep) находит синтаксические структуры; ast-edit меняет Clojure-код структурно, без текстовых замен.
  - **ponytail** - skill для небольших, прямых изменений без лишней абстракции.
- **Модели** - Luna была основной моделью; GPT-6.1-sol использовался как советник.


