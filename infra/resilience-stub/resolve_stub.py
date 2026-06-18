#!/usr/bin/env python3
"""A tiny fault-injecting stand-in for the user-management resolve endpoint, for the B3 resilience e2e.

It serves ``GET /internal/effective-role`` (the contract ``HttpRoleDefinitionSupplier`` calls) and
injects a controlled outage so the e2e can prove the two halves of the B3 headline through the gateway:

* **E1 — transient recovers within budget → the request SUCCEEDS.** With ``STUB_MODE=transient`` the
  stub returns ``STUB_FAILS`` (default 1) consecutive ``503``s **per distinct caller key**, then the real
  resolved role — so the catalog's resolve ``CallGuard`` (2 retries) rides out the blip and the protected
  request resolves the narrowed role instead of denying.
* **E2 — sustained outage → the request STILL DENIES.** With ``STUB_MODE=down`` every call returns ``503``;
  the resolve guard exhausts its budget and ``HttpRoleDefinitionSupplier`` throws ``RoleResolutionException``
  → the gate denies (B2's wall, un-breached — no realm-fallback widening).

Deliberately the **smallest** thing that injects "N-transient-then-recover" + "stay-down": no framework,
no image build (run on ``python:3.12-alpine`` with this file mounted). The resolved role it returns on
recovery is a tag-unconstrained editor on the governing team, so a protected write succeeds once resolved.

Env:
* ``STUB_MODE``  — ``transient`` (default) | ``down`` | ``up``
* ``STUB_FAILS`` — transient failures before recovery, per caller key (default ``1``)
* ``STUB_PORT``  — listen port (default ``8080``, matching the user-mgmt service it stands in for)
"""

import json
import os
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

MODE = os.environ.get("STUB_MODE", "transient")
FAILS = int(os.environ.get("STUB_FAILS", "1"))
PORT = int(os.environ.get("STUB_PORT", "8080"))

# The role the stub resolves on recovery / in 'up' mode: an editor on the governing team, no tag gate,
# so a protected catalog write is allowed once the role resolves. Shape = core.RoleDefinition JSON.
RESOLVED_ROLE = {
    "code": "team-editor",
    "attributes": {"role_level": 30},
    "permissions": {"catalog": ["READ", "WRITE", "TAG"], "category": ["READ", "WRITE", "TAG"],
                    "product": ["READ", "WRITE", "TAG"]},
}

# Per-caller transient-failure counters, so each distinct (userId,resourceType,resourceId) gets its own
# N-then-recover sequence (a fresh e2e request is not pre-failed by a previous one).
_seen = defaultdict(int)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802 (http.server API)
        parsed = urlparse(self.path)
        if parsed.path != "/internal/effective-role":
            self._send(404, {"error": "not found"})
            return

        if MODE == "down":
            self._send(503, {"error": "resolve source unavailable (sustained outage)"})
            return
        if MODE == "up":
            self._send(200, RESOLVED_ROLE)
            return

        # transient: STUB_FAILS 503s per caller key, then the resolved role
        q = parse_qs(parsed.query)
        key = (q.get("userId", [""])[0], q.get("resourceType", [""])[0], q.get("resourceId", [""])[0])
        _seen[key] += 1
        if _seen[key] <= FAILS:
            self._send(503, {"error": "resolve source unavailable (transient blip %d/%d)"
                             % (_seen[key], FAILS)})
        else:
            self._send(200, RESOLVED_ROLE)

    def _send(self, status, body):
        payload = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if status != 204:
            self.wfile.write(payload)

    def log_message(self, format, *args):  # noqa: A002 — match the base signature; quieter logs
        pass


if __name__ == "__main__":
    print("resolve-stub: mode=%s fails=%d port=%d" % (MODE, FAILS, PORT), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
