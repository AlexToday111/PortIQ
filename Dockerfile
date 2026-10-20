# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# Tests run separately with ./mvnw verify; Docker is not available in this build stage.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-noble AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system tradelab \
    && useradd --system --gid tradelab --home-dir /app tradelab
WORKDIR /app
COPY --from=build --chown=tradelab:tradelab /workspace/target/tradelab-portfolio-0.0.1-SNAPSHOT.jar app.jar
USER tradelab
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD curl --fail --silent --show-error http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
