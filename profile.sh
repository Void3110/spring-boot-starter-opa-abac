#!/usr/bin/env bash
#
# profile.sh — local infrastructure helper for the spring-boot-starter-opa-abac example.
#
# Uses Docker Compose (compose.yaml). Phase 0 brings up Postgres for
# catalog-management-service; later phases add Keycloak / APISIX / OPA / Jaeger.
#
# Usage:
#   ./profile.sh up         Start infrastructure (detached) and wait until healthy
#   ./profile.sh down       Stop and remove containers (keeps the data volume)
#   ./profile.sh down -v    Stop and remove containers AND the data volume
#   ./profile.sh restart    down + up
#   ./profile.sh status     Show container status
#   ./profile.sh health     Wait until all services report healthy
#   ./profile.sh logs [svc] Tail logs (all services, or one)
#   ./profile.sh psql       Open a psql shell in the postgres container
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"
PROJECT="opa-abac-example"

compose() {
  docker compose -p "$PROJECT" -f "$COMPOSE_FILE" "$@"
}

wait_healthy() {
  echo "Waiting for services to become healthy..."
  local deadline=$(( SECONDS + 120 ))
  while (( SECONDS < deadline )); do
    # Any container that has a healthcheck must report "healthy"; others just "running".
    local unhealthy
    unhealthy="$(compose ps --format '{{.Service}} {{.Health}} {{.State}}' \
      | awk '$2 != "healthy" && !($2 == "" && $3 == "running") { print $1 }')"
    if [[ -z "$unhealthy" ]]; then
      echo "All services healthy."
      compose ps
      return 0
    fi
    sleep 3
  done
  echo "ERROR: services did not become healthy in time:" >&2
  compose ps >&2
  return 1
}

cmd="${1:-}"
shift || true

case "$cmd" in
  up)
    compose up -d "$@"
    wait_healthy
    ;;
  down)
    compose down "$@"
    ;;
  restart)
    compose down
    compose up -d
    wait_healthy
    ;;
  status)
    compose ps
    ;;
  health)
    wait_healthy
    ;;
  logs)
    compose logs -f "$@"
    ;;
  psql)
    compose exec postgres psql -U catalog -d catalog "$@"
    ;;
  *)
    grep -E '^#( |$)' "$0" | sed -E 's/^# ?//'
    exit 1
    ;;
esac
