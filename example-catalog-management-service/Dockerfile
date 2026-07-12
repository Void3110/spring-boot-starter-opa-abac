# Multi-stage build for the catalog-management-service example app.
#
# Stage 1 builds the bootJar with the Gradle wrapper (so the image is reproducible from a
# clean checkout, no host Gradle needed). Stage 2 is a slim JRE runtime.
#
# Built and run via ../deploy.sh (the local container-pool rig). Not published.

# --- Stage 1: build -----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the whole repo so the multi-module Gradle build can resolve the library modules
# the example depends on. .dockerignore keeps build/ and caches out.
COPY . .

# Build only the example app's bootJar (and the library modules it needs), skip tests/checks
# — tests need Testcontainers, which isn't available at image-build time.
RUN ./gradlew :example-catalog-management-service:bootJar -x test -x check --no-daemon

# --- Stage 2: runtime ---------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl for the compose healthcheck (hits /actuator/health).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# OpenTelemetry Java agent — baked into the image so pods can enable tracing with no network
# at run time. deploy.sh adds -javaagent:/opt/otel/agent.jar + OTEL_* env when tracing is on.
ARG OTEL_AGENT_VERSION=2.29.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /opt/otel/agent.jar

COPY --from=build /workspace/example-catalog-management-service/build/libs/*.jar /app/app.jar

EXPOSE 8080

# JAVA_OPTS is injected per-pod by deploy.sh (heap limits + optional OTEL agent).
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
