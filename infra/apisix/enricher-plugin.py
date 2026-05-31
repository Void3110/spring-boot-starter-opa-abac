#!/usr/bin/env python3
"""Emit the APISIX `serverless-pre-function` plugin JSON for the demo identity enricher.

Building Lua-inside-JSON-inside-bash by hand is error-prone, so init-routes.sh shells out to
this helper, which json-encodes the Lua safely. Output is a single JSON fragment:

    "serverless-pre-function": { ... }

DEMO / THROWAWAY: this runs in the `access` phase AFTER openid-connect has *validated* the
token. It only *reads* the already-verified JWT payload (no signature check here) and injects
identity headers so you can see identity reach OPA + the app. Replaced by Spring-native
AbacContext extraction in the library (Phase 3).
"""
import json

# Lua: decode the JWT payload (2nd segment) from the Authorization header and set headers.
# Uses APISIX's bundled cjson + ngx.base64. Defensive: bails quietly on any malformed input.
LUA = r"""
return function(conf, ctx)
    local core = require("apisix.core")
    local auth = core.request.header(ctx, "Authorization")
    if not auth then return end
    local token = string.match(auth, "Bearer%s+(.+)")
    if not token then return end

    -- JWT = header.payload.signature ; we want the payload (2nd part).
    local parts = {}
    for p in string.gmatch(token, "[^%.]+") do parts[#parts + 1] = p end
    if #parts ~= 3 then return end

    -- base64url-decode the payload (pad to a multiple of 4).
    local payload_b64 = parts[2]:gsub("-", "+"):gsub("_", "/")
    local pad = #payload_b64 % 4
    if pad > 0 then payload_b64 = payload_b64 .. string.rep("=", 4 - pad) end

    local ok, raw = pcall(ngx.decode_base64, payload_b64)
    if not ok or not raw then return end
    local ok2, claims = pcall(require("cjson").decode, raw)
    if not ok2 or type(claims) ~= "table" then return end

    if claims.sub then core.request.set_header(ctx, "X-User-Id", claims.sub) end
    if claims.preferred_username then
        core.request.set_header(ctx, "X-Username", claims.preferred_username)
    end
end
"""

# Priority pins the execution order (higher runs first), matching the portal's proven chain:
#   openid-connect (2599)  ->  this enricher (2500)  ->  opa (2000)
# i.e. authenticate, then enrich identity headers, then authorize. Without this override
# serverless-pre-function defaults to a very high priority and would run BEFORE openid-connect
# (no validated token yet).
plugin = {
    "_meta": {"priority": 2500},
    "phase": "access",
    "functions": [LUA.strip()],
}

# Emit just the keyed fragment so init-routes.sh can splice it into the plugins object.
print('"serverless-pre-function":' + json.dumps(plugin))
