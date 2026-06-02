# Multi-stage build for the user-management-service example app (the ABAC attribute source).
#
# Stage 1 builds the bootJar with the Gradle wrapper (reproducible from a clean checkout, no host
# Gradle). Stage 2 is a slim JRE runtime. Built and run via ../deploy.sh (the local rig) when
# ENABLE_USER_SERVICE=1. Not published.

# --- Stage 1: build -----------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy the whole repo so the multi-module Gradle build can resolve the library modules.
COPY . .

# Build only this app's bootJar (+ the library modules it needs); skip tests/checks (they need
# Testcontainers, unavailable at image-build time).
RUN ./gradlew :example-user-management-service:bootJar -x test -x check --no-daemon

# --- Stage 2: runtime ---------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl for the compose healthcheck (hits /actuator/health).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/example-user-management-service/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
