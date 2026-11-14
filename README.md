# Решись Авиа

Веб-приложение для оценки спроса и продаж на авиарейсах. Scriptorium связывает Malli-схемы и Datahike с Pathom EQL и HTTP; Datastar обновляет результаты без перезагрузки страницы.

Пользователь выбирает города, дату и цену. Сервер берёт медианный прогноз спроса и считает продажи:

```text
спрос = медианный спрос − чувствительность к цене × (цена − базовая цена)
продажи = min(места, max(0, спрос))
```

На странице также есть график девяти квантилей спроса и вместимости. Регистрация, вход и права доступа работают через общий EQL endpoint. Города и даты доступны только для маршрутов пользователя.

## Запуск и данные

Запустить приложение: `clojure -M:dev`. В nREPL выполнить `(require 'user)` и `(integrant.repl/go)`.

`bb import_csv.clj` загружает CSV из `import.edn`: маршруты и прогнозы. Чтобы указать свои файлы, передайте пути: `bb import_csv.clj routes.csv forecast.csv`. Для ML-части проекта используйте свой `my_forecast.csv`; если его нет, подойдёт `seasonal_forecast.csv`. Повторный импорт добавляет новые записи прогнозов.

## Проверки и публикация

Тесты приложения:

```sh
clojure -M:test -e "(require 'clojure.test 'app.user-test 'app.mutations.user-test 'app.acl-test 'app.resolvers.scenario-test) (let [r (clojure.test/run-tests 'app.user-test 'app.mutations.user-test 'app.acl-test 'app.resolvers.scenario-test)] (System/exit (+ (:fail r) (:error r))))"
bb -cp .:test -e "(require 'import-csv-test) (let [r (clojure.test/run-tests 'import-csv-test)] (System/exit (+ (:fail r) (:error r))))"
```

Сборка: `clojure -T:build uber`. Для запуска JAR задайте `DATABASE_PATH` и `PORT`; данные приложения хранятся в Datahike. Перед переносом базы остановите сервер и скопируйте каталог `data/routes-db`.

Кейс разработан с помощью OpenAI Codex (`gpt-6.1-sol`).