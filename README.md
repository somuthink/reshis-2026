# Решись Авиа

Веб-приложение для оценки спроса и продаж на авиарейсах. Scriptorium связывает Malli-схемы и Datahike с Pathom EQL и HTTP; Datastar обновляет результаты без перезагрузки страницы.

Пользователь выбирает города, дату и цену. Сервер берёт медианный прогноз спроса и считает продажи:

```text
спрос = медианный спрос − чувствительность к цене × (цена − базовая цена)
продажи = min(места, max(0, спрос))
```

На странице также есть график девяти квантилей спроса и вместимости. Регистрация, вход и права доступа работают через общий EQL endpoint. Города и даты доступны только для маршрутов пользователя.

## Запуск и данные

Запусти `clojure -M:dev`; в nREPL выполни `(require 'user)` и `(integrant.repl/go)`, чтобы создать схему. Перед отдельным импортом останови систему командой `(integrant.repl/halt)`, запусти `bb import_csv.clj`, затем снова выполни `(integrant.repl/go)`. Импорт читает маршруты и прогнозы из `import.edn`; свои CSV можно указать так: `bb import_csv.clj routes.csv forecast.csv`. Для ML-части используй `my_forecast.csv`, а если его нет - `seasonal_forecast.csv`. Повторный импорт добавляет прогнозы, не заменяет старые.

## Проверки и публикация

Тесты приложения:

```sh
clojure -M:test -e "(require 'clojure.test 'app.user-test 'app.mutations.user-test 'app.acl-test 'app.resolvers.scenario-test) (let [r (clojure.test/run-tests 'app.user-test 'app.mutations.user-test 'app.acl-test 'app.resolvers.scenario-test)] (System/exit (+ (:fail r) (:error r))))"
bb -cp .:test -e "(require 'import-csv-test) (let [r (clojure.test/run-tests 'import-csv-test)] (System/exit (+ (:fail r) (:error r))))"
```

Сборка: `clojure -T:build uber`. Для запуска JAR задайте `DATABASE_PATH` и `PORT`; данные приложения хранятся в Datahike. Перед переносом базы остановите сервер и скопируйте каталог `data/routes-db`.

Кейс разработан с помощью OpenAI Codex (`gpt-6.1-sol`).