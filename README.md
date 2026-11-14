# Airline demand demo

## Run and import

Start with `clojure -M:dev`. In nREPL, evaluate `(require 'user)` and `(integrant.repl/go)` to initialize the database schema. Before importing from another process, evaluate `(integrant.repl/halt)` to release the file database; the nREPL itself stays running.
The app and importer share the file database at `data/routes-db`.


```sh
bb import_csv.clj
# Or use local files:
bb import_csv.clj routes.csv seasonal_forecast.csv
# Override only forecasts (keep the default routes source):
bb import_csv.clj --forecasts-source=../ml/my_forecast.csv
```

Default CSV sources live in `import.edn`: routes use the configured GitHub URL, and forecasts use `../ml/my_forecast.csv`. Edit either value without changing code; both accept URLs or local paths. Named options (`--routes-source`, `--forecasts-source`) or positional arguments override the configured defaults. Run the importer from this directory.

The importer creates shared city entities using the route codes (`MOW`, `AER`, etc.), links each route to its origin and destination cities, and imports forecasts. There is no demo/root entity. Re-importing creates new forecast entities; it is not a forecast replacement operation.

Evaluate `(integrant.repl/go)` again, then open `http://localhost:3000/` and register an account. Select Москва → Сочи, choose an available date, and enter a ticket price. For `seasonal_forecast.csv`, June 1 at `10000` gives 900 seats and 634 predicted sales; other imported forecasts can differ. Only five outbound routes from Moscow are provided. The date selector offers only dates present in the selected route's forecasts; direct submissions of unavailable city pairs or dates still display an error.

### Styles

Tailwind CSS v4.3.3 runs through the standalone CLI, without Node/npm:

```sh
bin/build-css                 # Build resources/public/css/app.css
bin/build-css --watch=always  # Rebuild while editing views or input.css
```

The input is `resources/public/css/input.css`; Tailwind scans `src/app` for classes. The page serves the compiled CSS locally, along with an original SVG sky background. The tool pairs the form with results on desktop and stacks them on mobile. Layered borders and inset highlights give the panel, recessed 56px controls, and raised button a clay-like finish. Results emphasize sales, with demand and capacity beneath. Sky colors are inspired by [Deta Surf](https://deta.surf/); no reference-site assets or fonts are required. Generated field controls retain their styling after Datastar patches.

`bin/tailwindcss` is the macOS ARM64 binary copied from the local Theta app. Replace it with the matching Tailwind v4 standalone binary when building on another platform. The compiled stylesheet is included, so serving the app does not require the CLI.


## Scriptorium flow

`src/app/resolvers/route.clj` owns the three global, parameterized option operations: `:route/origin-cities`, `:route/destination-cities`, and `:route/forecast-dates`. These have no source entity, so they are registered Pathom resolvers. Entity-derived attributes instead use plain functions declared through EDN. Optional `:to`/`:from` params filter cities by the opposite selection; date options require both selections and reuse the unique route lookup. Evaluation calls `app.mutations.scenario/evaluate`, which validates form values and returns a transient scenario. It performs no database writes; Pathom resolves the mutation's return join.

All scenario read resolvers and calculation helpers live in `src/app/resolvers/scenario.clj`: route selection, forecast selection, estimate, and quantile distribution. `src/app/mutations/scenario.clj` contains only the submission mutation and its registration. The resolvers' `:resolve` and `:in` declarations live in `scenario.edn`; Scriptorium generates the Pathom operations and infers reference joins. Selection functions return identity references only; generated Datahike resolvers fetch requested pricing/demand fields. Missing matches become `nil`; the estimate reports unavailable routes/dates without separate error attributes. Ambiguous matches raise a resolver error.

The page bootstraps `{:scenario/id "current"}` and requests `:scenario/>form`. Its `:in` composes four generated field controls. Picker `:>in` dependencies supply option lists only to generated views, never scalar reads. `:>options` describes option source/value/label keys, with optional `:sort-by`; `:>style` controls visual styling. The date field is disabled until dates are available, and a route change retains the chosen date only if it is still offered. Generic select renderers disable empty lists and synchronize bound values with the available options, without route/scenario-specific logic. A stable container owns the Datastar change request, refreshing city and date controls without replacing the handler.

Date uses `:time/local-date` with `:>style :date`. Its renderer directly builds a native `input[type=date]` and previous/next buttons; it does not delegate to a string/select renderer or inspect hidden select markup. Native calendar bounds come from the first/last available dates. Arrows use the supplied dates to skip gaps. Native calendars cannot disable individual interior dates, so unavailable dates fail custom validity and do not trigger automatic calculation. The browser controls date display formatting; values sent to the backend and stored in CSV remain ISO strings. The submission boundary parses a real `java.time.LocalDate` and rejects impossible calendar dates; forecast lookup converts back to ISO for stored data.

Price uses the generic `[:double :slider]` field renderer, selected by `:>style :slider` on `:scenario/price`. Its `:>in` joins route price bounds; `:>options` maps the source and minimum/maximum keys. The existing date-options lookup also returns its selected route identity, so no separate slider resolver or view attribute is needed. The generated field combines a numeric input with a whole-ruble slider. The numeric input retains exact decimals and accepts prices outside the slider range. Route changes refresh bounds without changing the entered price.

Changes to origin, destination, date, or price automatically call `app.mutations.scenario/evaluate` after a 300 ms trailing debounce, provided the form is complete and valid. Date arrows and slider input use the same signal-based trigger; there is no calculate button. Incomplete selections do not submit, and clearing a route/date hides the previous result.

The submission boundary declares its validation schema with `::params/schema` (`scriptorium.pathom.params`). The standard Pathom environment decodes and validates the mutation arguments. Returned city references, date, and price become graph inputs for the selection resolvers. Picker filters remain query params. Queries render fields/options and previews; submission mutations validate input, perform an action, and return data for normal resolver joins. Persistent forms can return a saved identity instead of transient data.

Example HTTP payload:

```json
{"entity":{},"query":"[{(app.mutations.scenario/evaluate {:from \"MOW\" :to \"OVB\" :date \"2026-06-01\" :price 10000}) [:scenario/>result]}]"}
```

The result join resolves route → forecast → estimate/distribution → view. There is no `:scenario/current` adapter attribute, scenario persistence, or custom endpoint. `/eql` retains its existing identity-only entity validation. Existing Scriptorium `:where` queries require a stored source entity, so the virtual scenario uses plain lookup functions without changing framework query semantics.

The formula follows the PDF's development case:

```text
demand = median demand - price sensitivity * (ticket price - base price)
sales  = min(seats, max(0, demand))
```

The scenario uses median (`q50`) demand, preserving raw demand and clamping sales only. It does not calculate profit or enforce route price bounds. Only the interactive calculator loads the chart module, which imports the combined Datastar + Rocket bundle pinned to v1.0.5 from jsDelivr. Other pages load Datastar without the chart for account mutations.

### Demand chart

`:scenario/distribution` adjusts all nine baseline quantiles server-side using the same price-response function as the summary. The ECharts plot shows demand against quantile probability, highlights the median, and marks capacity. Demand is not clamped: the chart median matches the summary's raw demand, even when sales are zero or capacity-limited. Discrete quantile points do not imply an exact density or extrapolated tails.

`resources/public/js/demand-chart.js` defines a Rocket component with JSON `points` and numeric `capacity` props. Rocket handles prop decoding, observation, shadow DOM, and lifecycle hooks; `renderOnPropChange: false` preserves ECharts-owned nodes during updates. ECharts 5.5.0 loads lazily from jsDelivr when a chart is mounted. The component resizes with its container and registers observer/chart disposal with Rocket's `cleanup`, including removal by error results. A screen-reader table provides the nine numeric quantiles. Rocket remains beta, so its combined bundle is version-pinned.


## Checks

```sh
clojure -M:test -e "(require 'clojure.test 'app.user-test 'app.mutations.user-test 'app.acl-test 'app.resolvers.scenario-test) (let [r (clojure.test/run-tests 'app.user-test 'app.mutations.user-test 'app.acl-test 'app.resolvers.scenario-test)] (System/exit (+ (:fail r) (:error r))))"
bb -cp .:test -e "(require 'import-csv-test) (let [r (clojure.test/run-tests 'import-csv-test)] (System/exit (+ (:fail r) (:error r))))"
```

AI assistance: OpenAI Codex (`gpt-6.1-sol`) through omp.

## Accounts and permissions

`/login`, `/register`, and `/account` use `page/defpage`, just like the calculator and route pages. Registration cities come from the public `:user/registration-cities` root resolver. Reads live in `app.resolvers.user`; login, registration, and logout are `app.mutations.user` mutations on the single `/eql`. There is no auth entity, accounts component, or auth-specific endpoint. `:user/password-hash` is declared in `user.edn` with `:readable? false` and `:editable? false`: its DB schema is generated, but graph reads, fields, and generic writes cannot expose or change it. Buddy Hashers derives salted PBKDF2-SHA256 at 600,000 iterations; existing hashes from the earlier encoding remain verifiable. Concurrent duplicate registrations cannot overwrite users.

User EDN declares separate `:user/>login-form`, `:user/>register-form`, and persistent `:user/>form` resolvers. Login/register pages seed a temporary string `:user/id` and empty email for field rendering only; they do not create a database entity. `/account` uses the session's real ID and resolves its email through the protected stored-data resolver. Generated `:user/>email`, `:user/>password`, and option-backed `:user/>cities` fields compose the forms. The password field renderer never echoes a value; registration only adjusts the generated control's new-password autocomplete and length hint. `:user/password` and demo `:user/cities` are attribute-level `:stored? false`, excluded from DB schema, generated reads, and generic writes. There are no explicit password-view resolvers, manual password-hash schema transactions, or form-mode attribute.

User mutations return `:user/authenticated?`, `:user/errors`, and the user identity on successful login/registration. They neither import views nor return Hiccup/view attributes. The caller requests `:user/>auth-result` in the mutation return join. Its EDN declaration supplies `:in [:user/authenticated? :user/errors]`, `:entity-input? false`, and `:scriptorium/public? true`: anonymous failures render from transient result data without an invented user identity or persisted feedback fields.

Operation authorization is default-deny, including for authenticated callers: unannotated or conflicting policies are forbidden. `:scriptorium/public?` is Scriptorium's explicit anonymous-operation exception, not an EACL library key or stored grant. Login, registration, registration-city options, and public form/feedback rendering use it; their stored-data dependencies still require authorization. Field property `:>public?` applies only to the generated view, never its stored scalar. Generated entity reads/views retain their normal `view` policy; generic graph writes retain `transact`. Scenario EDN declares `:eacl/resource :app.acl/subject-resource`, applied during generation to its derived and field operations. No application component scans or rewrites generated resolver metadata.

Registration offers demo-only self-selected city permissions. A route is granted only when both endpoint cities are selected. Grants are native EACL `viewer` relationships, not persisted `:user/routes` or `:user/cities` attributes. Route access propagates to its cities and forecasts through existing domain references. Scriptorium reconciles only domain-derived relationships, leaving explicit grants intact. Options and city route lists filter unauthorized routes before resolving children; generic graph writes cannot assign permissions.

City pickers and city-page route lists obtain authorized route IDs through paginated EACL `lookup-resources`, then constrain Datalog with a collection input. Picker queries also enforce directional connectivity and the opposite city selection; they return city identities only, leaving names to Pathom joins. Native `with-snapshot` keeps authorization lookup and domain reads on the same immutable snapshot. `scriptorium.eacl/lookup-resource-ids` only collects pages for its supplied query, without hardcoding a resource type or permission. Normal operation-level ACL enforcement remains enabled. Scenario mutations authorize the selected route before returning, regardless of the requested return projection.

Ordinary Ring `wrap-session` owns server-side memory sessions and cookies. Sessions rotate on login/registration and are revoked on logout. Cookies are HttpOnly, SameSite=Lax, and Secure for HTTPS requests. Sessions expire when the component/JVM restarts; users persist. Thin HTTP middleware supplies the authenticated ACL subject, rejects unsafe requests without an exact matching Origin, and marks dynamic responses no-store. Behind an HTTPS proxy, ensure Ring receives the correct trusted request scheme.

Mutations attach server-only response transformations with `scriptorium.reitit.eql/augment-response`, following Fulcro's response augmentation model. The shared endpoint applies them only after the complete query succeeds and returns an ordinary finite SSE Ring response, so standard session middleware can set cookies before headers are sent. Session state is not graph output; there are no request-local session callbacks or manual session finalizers. Virtual `scenario/current` uses the authenticated user's ACL resource, while selected route/forecast data retains its own authorization.

Demo scope: self-assigned grants are not administrator-controlled authorization. No password recovery, email verification, rate limiting, persistent session store, or deployment setup is included. User creation and grant assignment are separate writes: a grant failure leaves a registered user without grants and does not issue a session.
