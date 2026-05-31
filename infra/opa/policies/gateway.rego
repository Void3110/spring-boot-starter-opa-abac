# Gateway authorization policy — Phase B placeholder: ALLOW EVERYTHING.
#
# This exists so the full topology (APISIX -> OPA -> app) is wired and traced end to end,
# even though we're not enforcing anything yet. Later phases replace this with a real ABAC
# policy that inspects the request + subject attributes.
#
# The APISIX `opa` plugin POSTs { "input": { ...request... } } to
#   http://opa:8181/v1/data/gateway
# and reads `result.allow`. We return allow=true unconditionally here.

package gateway

# Explicit default deny is the habit we want everywhere — but this placeholder then
# unconditionally allows. Swap the `allow = true` rule for real conditions later.
default allow := false

allow := true
