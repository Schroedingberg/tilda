# Build stage
FROM clojure:temurin-21-tools-deps-alpine AS builder

WORKDIR /build

# Cache dependencies
COPY deps.edn .
RUN clojure -P

# Build uberjar
COPY . .
RUN clojure -T:build uber

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/target/tilda.jar /app/tilda.jar
COPY config.edn /app/

EXPOSE 8080

CMD ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-jar", "tilda.jar"]
