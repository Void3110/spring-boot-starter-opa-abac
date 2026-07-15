#!/usr/bin/env bash
# Bootstrap the local SonarQube: set the admin password, seed an owned copy of the built-in
# "Sonar way" Java profile, create the no-new-issues quality gate, bind both to THIS project
# (never instance-wide defaults), and mint an analysis token for local scans.
#
# Idempotent: safe to re-run. Writes the analysis token to .sonar-local/token (gitignored).
#
# Everything is PROJECT-SCOPED (api/qualityprofiles/add_project + api/qualitygates/select),
# so this bootstrap can also target an already-running instance on the same analyzer version as
# the compose pin (SONAR_LOCAL_URL=... SONAR_LOCAL_ADMIN_PW=... ./.sonar-local/bootstrap.sh)
# without fighting other projects over the instance defaults.
#
# Prereq: docker compose -f .sonar-local/docker-compose.yml up -d   (and the server UP)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SONAR_URL="${SONAR_LOCAL_URL:-http://localhost:19244}"
# An owned, non-built-in copy of "Sonar way" (Java) — editable, so local strictness can be
# tightened later without touching the built-in profile.
PROFILE_NAME="OPA-ABAC Local Java"
PROJECT_KEY="spring-boot-starter-opa-abac-local"
PROJECT_NAME="spring-boot-starter-opa-abac (local)"
GATE_NAME="OPA-ABAC Local — no new issues"
TOKEN_FILE="$HERE/token"
DEFAULT_ADMIN_PW="admin"
# SonarQube 26.x enforces a password policy (>=1 upper, lower, digit, special). Keep the default compliant.
NEW_ADMIN_PW="${SONAR_LOCAL_ADMIN_PW:-OpaAbacLocal!2026x}"
PY="${SONAR_LOCAL_PY:-python3}"

log() { printf '\033[1;34m[bootstrap]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[bootstrap] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# --- 0. server must be UP -----------------------------------------------------
log "waiting for $SONAR_URL to be UP..."
for i in $(seq 1 60); do
  if curl -sf "$SONAR_URL/api/system/status" 2>/dev/null | grep -q '"status":"UP"'; then break; fi
  [ "$i" = 60 ] && die "server did not reach UP; is the stack started?"
  sleep 5
done
log "server is UP"

# --- 1. admin credentials -----------------------------------------------------
# SonarQube ships admin/admin and forces a change on first login. Detect which pw works.
ADMIN_PW=""
if curl -sf -u "admin:$NEW_ADMIN_PW" "$SONAR_URL/api/authentication/validate" | grep -q '"valid":true'; then
  ADMIN_PW="$NEW_ADMIN_PW"; log "admin already using the local password"
elif curl -sf -u "admin:$DEFAULT_ADMIN_PW" "$SONAR_URL/api/authentication/validate" | grep -q '"valid":true'; then
  log "changing default admin password..."
  curl -sf -u "admin:$DEFAULT_ADMIN_PW" -X POST "$SONAR_URL/api/users/change_password" \
    --data-urlencode "login=admin" \
    --data-urlencode "previousPassword=$DEFAULT_ADMIN_PW" \
    --data-urlencode "password=$NEW_ADMIN_PW" >/dev/null
  ADMIN_PW="$NEW_ADMIN_PW"; log "admin password set"
else
  die "cannot authenticate as admin (tried the local and the factory-default password); if targeting a shared instance, pass SONAR_LOCAL_ADMIN_PW"
fi
AUTH=(-u "admin:$ADMIN_PW")

# --- 2. the project (created up-front so profile + gate can bind before the first scan) ---
if curl -sf "${AUTH[@]}" "$SONAR_URL/api/components/show?component=$PROJECT_KEY" >/dev/null 2>&1; then
  log "project '$PROJECT_KEY' already exists"
else
  log "creating project '$PROJECT_KEY'..."
  curl -sf "${AUTH[@]}" -X POST "$SONAR_URL/api/projects/create" \
    --data-urlencode "project=$PROJECT_KEY" \
    --data-urlencode "name=$PROJECT_NAME" >/dev/null || die "project creation failed"
  log "project created"
fi

# --- 3. an owned Java profile: copy of the built-in "Sonar way" ---------------
# The pinned analyzer's built-in "Sonar way" is the rule baseline; the copy is a
# non-built-in profile we OWN and can tighten later (back it up to XML once it diverges).
PROFILE_EXISTS=$(curl -sf "${AUTH[@]}" \
  "$SONAR_URL/api/qualityprofiles/search?language=java" \
  | $PY -c "import sys,json; ps=json.load(sys.stdin)['profiles']; print(sum(1 for p in ps if p['name']=='$PROFILE_NAME'))" 2>/dev/null || echo 0)
if [ "$PROFILE_EXISTS" = "0" ]; then
  log "copying built-in 'Sonar way' (java) -> '$PROFILE_NAME'..."
  SONARWAY_KEY=$(curl -sf "${AUTH[@]}" "$SONAR_URL/api/qualityprofiles/search?language=java" \
    | $PY -c "import sys,json; ps=json.load(sys.stdin)['profiles']; print(next(p['key'] for p in ps if p['isBuiltIn'] and p['name']=='Sonar way'))") \
    || die "could not find the built-in 'Sonar way' Java profile"
  curl -sf "${AUTH[@]}" -X POST "$SONAR_URL/api/qualityprofiles/copy" \
    --data-urlencode "fromKey=$SONARWAY_KEY" \
    --data-urlencode "toName=$PROFILE_NAME" >/dev/null || die "profile copy failed"
  log "profile created"
else
  log "profile '$PROFILE_NAME' already exists"
fi

# Bind the profile to THIS project (never set_default — a shared instance may host other
# projects with their own profiles).
curl -sf "${AUTH[@]}" -X POST "$SONAR_URL/api/qualityprofiles/add_project" \
  --data-urlencode "language=java" \
  --data-urlencode "qualityProfile=$PROFILE_NAME" \
  --data-urlencode "project=$PROJECT_KEY" >/dev/null \
  && log "'$PROFILE_NAME' bound to $PROJECT_KEY" || log "WARN: could not bind profile (may already be)"

# --- 4. the quality gate: zero NEW bugs/vulns/smells --------------------------
# Local intent = "my change adds no findings". (No coverage condition yet — the local gate
# owns RULE findings; a coverage floor is a possible later tightening.)
if ! curl -sf "${AUTH[@]}" "$SONAR_URL/api/qualitygates/show?name=$(printf %s "$GATE_NAME" | $PY -c "import sys,urllib.parse; print(urllib.parse.quote(sys.stdin.read()))")" >/dev/null 2>&1; then
  log "creating quality gate '$GATE_NAME'..."
  curl -sf "${AUTH[@]}" -X POST "$SONAR_URL/api/qualitygates/create" \
    --data-urlencode "name=$GATE_NAME" >/dev/null || true
  for m in new_bugs new_vulnerabilities new_code_smells; do
    curl -sf "${AUTH[@]}" -X POST "$SONAR_URL/api/qualitygates/create_condition" \
      --data-urlencode "gateName=$GATE_NAME" \
      --data-urlencode "metric=$m" --data-urlencode "op=GT" --data-urlencode "error=0" >/dev/null || true
  done
  log "gate created (new_bugs/new_vulnerabilities/new_code_smells all must be 0)"
else
  log "gate '$GATE_NAME' already exists"
fi
# Bind the gate to THIS project (project-scoped, same reasoning as the profile).
curl -sf "${AUTH[@]}" -X POST "$SONAR_URL/api/qualitygates/select" \
  --data-urlencode "gateName=$GATE_NAME" \
  --data-urlencode "projectKey=$PROJECT_KEY" >/dev/null \
  && log "gate bound to $PROJECT_KEY" || log "WARN: could not bind gate (may already be)"

# --- 5. analysis token --------------------------------------------------------
if [ -f "$TOKEN_FILE" ] && [ -s "$TOKEN_FILE" ]; then
  log "analysis token already present ($TOKEN_FILE)"
else
  log "minting a local analysis token..."
  TOK=$(curl -sf "${AUTH[@]}" -X POST "$SONAR_URL/api/user_tokens/generate" \
    --data-urlencode "name=opa-abac-local-scan-$(date +%s 2>/dev/null || echo t)" \
    --data-urlencode "type=GLOBAL_ANALYSIS_TOKEN" \
    | $PY -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null || true)
  [ -n "$TOK" ] || die "token generation failed"
  printf '%s' "$TOK" > "$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"
  log "token written to $TOKEN_FILE (gitignored)"
fi

log "bootstrap complete. Local Sonar: $SONAR_URL  (admin / $NEW_ADMIN_PW)"
log "run a scan with:  ./.sonar-local/sonar-local.sh"
