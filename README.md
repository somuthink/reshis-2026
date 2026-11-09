# Airline demand demo

## Run and import

Start with `clojure -M:dev`. In nREPL, evaluate `(require 'user)` and `(integrant.repl/go)` to initialize the database schema. Before importing from another process, evaluate `(integrant.repl/halt)` to release the file database; the nREPL itself stays running.

```sh
bb import_csv.clj
# Or use local files:
bb import_csv.clj routes.csv seasonal_forecast.csv
```

The importer creates shared city entities using the route codes (`MOW`, `AER`, etc.), links each route to its origin and destination cities, and imports forecasts. There is no demo/root entity. Re-importing creates new forecast entities; it is not a forecast replacement operation.

Evaluate `(integrant.repl/go)` again, then open `http://localhost:3000/`. Select Москва → Сочи, date `2026-06-01`, and price `10000`: the sample data gives 900 seats and 634 predicted sales. Only five outbound routes from Moscow are provided; unavailable city pairs and dates display an error.

### Styles

Tailwind CSS v4.3.3 runs through the standalone CLI, without Node/npm:

```sh
bin/build-css                 # Build resources/public/css/app.css
bin/build-css --watch=always  # Rebuild while editing views or input.css
```

The input is `resources/public/css/input.css`; Tailwind scans `src/app` for classes. The page serves the compiled CSS locally, along with an original SVG sky background. The tool pairs the form with results on desktop and stacks them on mobile. Layered borders and inset highlights give the panel, recessed 56px controls, and raised button a clay-like finish. Results emphasize sales, with demand and capacity beneath. Sky colors are inspired by [Deta Surf](https://deta.surf/); no reference-site assets or fonts are required. Generated field controls retain their styling after Datastar patches.

`bin/tailwindcss` is the macOS ARM64 binary copied from the local Theta app. Replace it with the matching Tailwind v4 standalone binary when building on another platform. The compiled stylesheet is included, so serving the app does not require the CLI.


## Scriptorium flow

`src/app/resolvers/route.clj` owns `:route/origin-cities` and `:route/destination-cities`. Optional `:to`/`:from` params filter by the opposite selection. Form submission calls the `app.scenario/evaluate` mutation, which validates form values and returns a transient scenario. It performs no database writes; Pathom resolves the mutation's return join.

Plain `[env entity]` functions in `src/app/resolvers/scenario.clj` select the route by city pair and its forecast by date. Their `:resolve` and `:in` declarations live in `scenario.edn`; Scriptorium generates the Pathom operations and infers their output joins from the reference types. The functions return identity references only; generated Datahike resolvers fetch requested pricing/demand fields. Missing matches become `nil`; the estimate reports unavailable routes/dates without separate error attributes. Ambiguous matches raise a resolver error.

The page bootstraps `{:scenario/id "current"}` and requests `:scenario/>form`. Its `:in` composes four generated field controls. Picker `:>in` dependencies supply the route-option lists only to generated views, never scalar reads. `:>options` describes option source/value/label keys; `:>style` controls visual styling; `:ui/type` selects the native date input. Generic renderers contain no route/scenario logic. A stable container in the form owns the Datastar change request; bubbling events refresh the controls without replacing their parent's handler.

The submission boundary declares its validation schema with `::params/schema` (`scriptorium.pathom.params`). The standard Pathom environment decodes and validates the mutation arguments. Returned city references, date, and price become graph inputs for the selection resolvers. Picker filters remain query params. Queries render fields/options and previews; submission mutations validate input, perform an action, and return data for normal resolver joins. Persistent forms can return a saved identity instead of transient data.

Example HTTP payload:

```json
{"entity":{},"query":"[{(app.scenario/evaluate {:from \"MOW\" :to \"OVB\" :date \"2026-06-01\" :price 10000}) [:scenario/>result]}]"}
```

The result join resolves route → forecast → estimate → view. There is no `:scenario/current` adapter attribute, scenario persistence, custom endpoint, or handwritten JavaScript. `/eql` retains its existing identity-only entity validation. Existing Scriptorium `:where` queries require a stored source entity, so the virtual scenario uses plain lookup functions without changing framework query semantics.

The formula follows the PDF's development case:

```text
demand = median demand - price sensitivity * (ticket price - base price)
sales  = min(seats, max(0, demand))
```

The scenario uses median (`q50`) demand, preserving raw demand and clamping sales only. It does not calculate profit or enforce route price bounds. The Datastar bundle is pinned to RC.7 and loaded from jsDelivr.

## Checks

```sh
clojure -M:test -e "(require 'clojure.test 'app.resolvers.scenario-test) (let [r (clojure.test/run-tests 'app.resolvers.scenario-test)] (System/exit (+ (:fail r) (:error r))))"
bb -cp .:test -e "(require 'import-csv-test) (let [r (clojure.test/run-tests 'import-csv-test)] (System/exit (+ (:fail r) (:error r))))"
```

AI assistance: OpenAI Codex (`gpt-6.1-sol`) through omp.

Local demo only: the configured fixed admin subject is shared by requests. Configure authentication and an operation-access policy before hosting; optional charts, registration, and hosting are not implemented.
