#!/usr/bin/env python3
"""The step-up ACR contract: OPA's `loa` map must agree with the realm's.

THE FOOTGUN
-----------
`data.step_up.required_acr` is what the challenge asks the client to obtain, and the policy already
refuses to mint a challenge unless that name maps to a NUMERIC level in `data.step_up.loa` — an
unmapped name fails closed to a plain deny (see the `deny_reason` guard in category.rego /
product.rego). That guard is about OPA's OWN data being coherent.

It cannot see the OTHER half of the contract. Keycloak decides which ACR values exist, in the realm
attribute `acr.loa.map`. Point `required_acr` at a name OPA knows and the realm does not — add
`"aal3": 3` to `step_up.loa` and set `required_acr: "aal3"` — and every guard passes: the policy
mints a well-formed RFC 9470 challenge, the console forwards it faithfully, and **Keycloak rejects
the authorization request outright** with `Invalid parameter: claims`.

That failure is the worst shape available. It does not downgrade and it does not authenticate: the
user lands on a Keycloak error page, and the client never sees a response it could explain, because
the request never became one. Nothing in the policy corpus, the Java build or the e2e suite reads
both files, so the drift is invisible until someone tries it in a browser.

WHAT IS CHECKED
---------------
1. `required_acr` is a key of OPA's `loa` map (belt-and-braces — the policy fails closed here
   anyway, but a plain deny where an operator expected a challenge is still a misconfiguration).
2. `required_acr` is a key the REALM can mint.
3. Any level present in both maps agrees. A name meaning 2 to OPA and 3 to Keycloak elevates
   nothing while looking correct on both sides.

Names the realm knows and OPA does not are FINE: the realm may offer levels this deployment does
not use.

USAGE
-----
    scripts/checks/check-step-up-acr.py [step_up.json] [realm-export.json]

Exit 0 agreed · 1 drift · 2 a file is missing or unreadable.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

DEFAULT_STEP_UP = Path("infra/opa/policies/step_up.json")
DEFAULT_REALM = Path("infra/keycloak/realm-export.json")


def load_opa(path: Path) -> tuple[dict, str]:
    data = json.loads(path.read_text()).get("step_up", {})
    return data.get("loa", {}), data.get("required_acr", "")


def load_realm(path: Path) -> dict:
    attrs = json.loads(path.read_text()).get("attributes", {}) or {}
    raw = attrs.get("acr.loa.map")
    if raw is None:
        raise KeyError("realm attribute 'acr.loa.map' is absent")
    # Keycloak stores it as a JSON STRING inside the attribute map.
    return json.loads(raw)


def main(argv: list[str]) -> int:
    step_up = Path(argv[1]) if len(argv) > 1 else DEFAULT_STEP_UP
    realm = Path(argv[2]) if len(argv) > 2 else DEFAULT_REALM

    try:
        opa_loa, required = load_opa(step_up)
        realm_loa = load_realm(realm)
    except (OSError, ValueError, KeyError) as e:
        print(f"ERROR cannot read the step-up contract: {e}", file=sys.stderr)
        return 2

    problems: list[str] = []

    if not required:
        problems.append(f"{step_up}: step_up.required_acr is absent or empty")
    else:
        if required not in opa_loa:
            problems.append(
                f"{step_up}: required_acr '{required}' is not a key of step_up.loa "
                f"({sorted(opa_loa)}) — the policy will fail closed to a plain deny "
                f"instead of challenging."
            )
        if required not in realm_loa:
            problems.append(
                f"required_acr '{required}' is not in the realm's acr.loa.map "
                f"({sorted(realm_loa)}) — the challenge would be well-formed and Keycloak would "
                f"reject the authorization request with 'Invalid parameter: claims'. The user "
                f"lands on a Keycloak error page and the client never sees a response."
            )

    for name in sorted(set(opa_loa) & set(realm_loa)):
        if opa_loa[name] != realm_loa[name]:
            problems.append(
                f"level drift for '{name}': step_up.loa says {opa_loa[name]}, the realm's "
                f"acr.loa.map says {realm_loa[name]} — elevation would prove the wrong level."
            )

    if problems:
        for p in problems:
            print(f"  ✗ {p}", file=sys.stderr)
        print(f"\n{len(problems)} step-up ACR problem(s).", file=sys.stderr)
        return 1

    print(
        f"step-up ACR: required_acr '{required}' is mintable by the realm; "
        f"{len(set(opa_loa) & set(realm_loa))} shared level(s) agree"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
