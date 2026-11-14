FROM babashka/babashka:latest AS babashka
FROM clojure:temurin-25-tools-deps AS build
WORKDIR /build/reshis-2026/app
RUN git clone https://github.com/somuthink/scriptorium.git /build/scriptorium \
    && git -C /build/scriptorium checkout 3c620a79461e0a919da672c1345155ca4769cd78
COPY deps.edn build.clj ./
RUN clojure -P -T:build
COPY src ./src
COPY resources ./resources
RUN clojure -T:build uber
COPY --from=babashka /usr/local/bin/bb /usr/local/bin/bb
COPY bb.edn import_csv.clj import.edn ./
COPY deploy/my_forecast.csv /build/reshis-2026/ml/my_forecast.csv
RUN java -cp target/reshis-2026.jar clojure.main -e \
    "(require 'app.main) (app.main/-main) (System/exit 0)"
RUN bb import_csv.clj

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/reshis-2026/app/target/reshis-2026.jar /app/app.jar
COPY --from=build /build/reshis-2026/app/data/routes-db /app/data/routes-db
ENV IP=0.0.0.0 PORT=10000 DATABASE_PATH=/app/data/routes-db
EXPOSE 10000
CMD ["java", "-Xms32m", "-Xmx192m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=32m", "-XX:ActiveProcessorCount=1", "-Dclojure.core.async.pool-size=2", "-jar", "/app/app.jar"]
