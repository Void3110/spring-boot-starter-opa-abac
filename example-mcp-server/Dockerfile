# Multi-stage build for the example MCP server (Phase 9 — the agent tool surface).
#
# Stage 1 builds the bootJar with the Gradle wrapper (reproducible from a clean checkout, no host
# Gradle). Stage 2 is a slim JRE runtime. Built and run via ../deploy.sh (the local rig) when
# ENABLE_MCP=1. Not published.

# --- Stage 1: build -----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the whole repo so the multi-module Gradle build can resolve the library modules.
COPY . .

# Build only this app's bootJar (+ the library modules it needs); skip tests/checks (some boot a
# Spring context and bind a port, which is not what an image build should do).
RUN ./gradlew :example-mcp-server:bootJar -x test -x check --no-daemon

# --- Stage 2: runtime ---------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# curl for the compose healthcheck (hits /actuator/health).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/example-mcp-server/build/libs/*.jar /app/app.jar

EXPOSE 8080

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
