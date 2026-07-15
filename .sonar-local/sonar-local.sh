#!/usr/bin/env bash
# Scan the WORKING TREE against the local SonarQube and print the findings. Use this as the
# pre-push static-analysis gate: catch S112/S1192/S125/... BEFORE committing/pushing, at the
# ★ architecture-review gate or during /deep-review Phase 4.
#
# The "OPA-ABAC Local Java" profile is an owned copy of the pinned 26.3.0 analyzer's built-in
# "Sonar way" rules. This public project has no hosted Sonar — this local scan IS the rule
# gate (no coverage condition yet; the gate owns RULE findings).
#
# Usage:
#   ./.sonar-local/sonar-local.sh                 # scan; findings on files CHANGED vs origin/main (default)
#   ./.sonar-local/sonar-local.sh --all           # full-tree view (every open finding — for an audit)
#   ./.sonar-local/sonar-local.sh --rules S112,S1192   # only these rules
#   ./.sonar-local/sonar-local.sh --no-scan       # skip the gradle scan, just re-query the last results
#
# Prereq: stack up + bootstrapped (docker compose -f .sonar-local/docker-compose.yml up -d ; ./.sonar-local/bootstrap.sh)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
SONAR_URL="${SONAR_LOCAL_URL:-http://localhost:19244}"
TOKEN_FILE="$HERE/token"
PROJECT_KEY="spring-boot-starter-opa-abac-local"   # a LOCAL key, distinct from any hosted project
PY="${SONAR_LOCAL_PY:-python3}"

# DEFAULT scope = CHANGED FILES (findings only on files in the current git diff vs origin/main).
# This is the per-ticket pre-push gate signal ("did MY change add a smell?"), and needs no Sonar
# leak-period baseline. --all shows the full-tree view (for an occasional audit).
RULES=""; SCOPE="changed"; DO_SCAN=1; DIFF_BASE="${SONAR_DIFF_BASE:-origin/main}"
while [ $# -gt 0 ]; do
  case "$1" in
    --rules) RULES="$2"; shift 2 ;;
    --all)   SCOPE="all"; shift ;;
    --changed) SCOPE="changed"; shift ;;
    --no-scan) DO_SCAN=0; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

log() { printf '\033[1;34m[sonar-local]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[sonar-local] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

curl -sf "$SONAR_URL/api/system/status" 2>/dev/null | grep -q '"status":"UP"' \
  || die "local Sonar not UP at $SONAR_URL — start it: docker compose -f .sonar-local/docker-compose.yml up -d"
[ -s "$TOKEN_FILE" ] || die "no analysis token — run ./.sonar-local/bootstrap.sh"
TOKEN="$(cat "$TOKEN_FILE")"

if [ "$DO_SCAN" = 1 ]; then
  log "scanning the working tree against $SONAR_URL (project=$PROJECT_KEY)..."
  # testClasses compiles main+test bytecode for the analyzer (Sonar reads bytecode); no test
  # execution and no coverage import — the local gate is findings-only.
  ( cd "$REPO" && ./gradlew testClasses sonar \
      -Dsonar.host.url="$SONAR_URL" \
      -Dsonar.token="$TOKEN" \
      -Dsonar.projectKey="$PROJECT_KEY" \
      -Dsonar.projectName="spring-boot-starter-opa-abac (local)" \
      --console=plain ) || die "gradle sonar failed (see output above)"
  log "waiting for the local server to finish processing the report..."
  for i in $(seq 1 30); do
    ce=$(curl -sf -u "$TOKEN:" "$SONAR_URL/api/ce/component?component=$PROJECT_KEY" 2>/dev/null \
         | $PY -c "import sys,json; q=json.load(sys.stdin).get('queue',[]); print(len(q))" 2>/dev/null || echo 1)
    [ "$ce" = 0 ] && break; sleep 2
  done
fi

# --- query the findings -------------------------------------------------------
Q="componentKeys=$PROJECT_KEY&types=CODE_SMELL,BUG,VULNERABILITY&statuses=OPEN,CONFIRMED,REOPENED&ps=500"
[ -n "$RULES" ] && Q="$Q&rules=$(echo "$RULES" | sed 's/\([^,]*\)/java:\1/g')"

# The set of repo-relative files changed in the working tree vs the diff base (staged+unstaged+untracked).
CHANGED=""
if [ "$SCOPE" = "changed" ]; then
  CHANGED=$( { git -C "$REPO" diff --name-only "$DIFF_BASE"...HEAD 2>/dev/null; \
               git -C "$REPO" diff --name-only 2>/dev/null; \
               git -C "$REPO" diff --name-only --cached 2>/dev/null; \
               git -C "$REPO" ls-files --others --exclude-standard 2>/dev/null; } \
             | sort -u | grep -E '\.java$' || true )
fi

RESP=$(curl -sf -u "$TOKEN:" "$SONAR_URL/api/issues/search?$Q" 2>/dev/null) || die "issue query failed"
echo "$RESP" | CHANGED_FILES="$CHANGED" SCOPE="$SCOPE" DIFF_BASE="$DIFF_BASE" $PY -c "
import sys, os, json
d = json.load(sys.stdin)
issues = d.get('issues', [])
scope = os.environ['SCOPE']
if scope == 'changed':
    changed = set(os.environ.get('CHANGED_FILES','').split())
    # component looks like '<projectKey>:opa-abac-core/src/main/.../X.java' -> repo-relative path
    def relpath(comp): return comp.split(':',1)[1] if ':' in comp else comp
    if not changed:
        print('\033[1;34m[sonar-local]\033[0m no changed .java files vs %s — nothing to gate (use --all for a full-tree audit).' % os.environ['DIFF_BASE']); sys.exit(0)
    issues = [i for i in issues if relpath(i['component']) in changed]
if not issues:
    tag = 'on changed files' if scope=='changed' else 'full-tree'
    print('\033[1;32m[sonar-local] CLEAN — 0 open findings (%s).\033[0m' % tag); sys.exit(0)
from collections import Counter
by = Counter(i['rule'].split(':')[-1] for i in issues)
scope_lbl = ('CHANGED files vs %s' % os.environ['DIFF_BASE']) if scope=='changed' else 'FULL TREE'
print('\033[1;33m[sonar-local] %d finding(s) [%s]:\033[0m  ' % (len(issues), scope_lbl) + ', '.join('%s×%d'%(r,n) for r,n in by.most_common()))
for i in sorted(issues, key=lambda x:(x['rule'], x['component'], x.get('line',0))):
    f = i['component'].split(':')[-1]
    print('  %-8s %s:%s  %s' % (i['rule'].split(':')[-1], f, i.get('line','?'), i['message'][:80]))
"
