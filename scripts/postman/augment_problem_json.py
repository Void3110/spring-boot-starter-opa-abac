#!/usr/bin/env python3
"""
Phase 5.9 T4 — augment existing newman matrices with RFC-7807 problem+json assertions.

Idempotent: for each targeted request it APPENDS contract assertions (problem+json content type,
the expected errorCode, the canonical members) to the existing test script — only if not already present.
- 403 requests  -> assert problem+json + errorCode == ACCESS_DENIED + canonical members
- 422 requests  -> assert problem+json + errorCode == TAG_VALUE_ILLEGAL
- selected 201s -> assert a Location header is present

No new requests, no new collection files. Run from scripts/postman/.
"""
import json
import sys

MARKER = "// [phase-5.9 contract]"  # idempotency guard

PROBLEM_ASSERTS = {
    "ACCESS_DENIED": [
        MARKER,
        "pm.test('403 body is RFC-7807 problem+json with ACCESS_DENIED', () => {",
        "  pm.expect(pm.response.headers.get('Content-Type') || '').to.include('application/problem+json');",
        "  const b = pm.response.json();",
        "  pm.expect(b.errorCode).to.eql('ACCESS_DENIED');",
        "  pm.expect(b.status).to.eql(403);",
        "  pm.expect(b).to.have.property('type');",
        "  pm.expect(b).to.have.property('title');",
        "  pm.expect(b).to.not.have.property('message');",
        "});",
    ],
    "TAG_VALUE_ILLEGAL": [
        MARKER,
        "pm.test('422 body is RFC-7807 problem+json with TAG_VALUE_ILLEGAL', () => {",
        "  pm.expect(pm.response.headers.get('Content-Type') || '').to.include('application/problem+json');",
        "  const b = pm.response.json();",
        "  pm.expect(b.errorCode).to.eql('TAG_VALUE_ILLEGAL');",
        "  pm.expect(b.status).to.eql(422);",
        "  pm.expect(b).to.not.have.property('message');",
        "});",
    ],
    "LOCATION": [
        MARKER,
        "pm.test('201 carries a Location header', () => {",
        "  pm.expect(pm.response.headers.get('Location') || '').to.match(/\\/api\\/v1\\//);",
        "});",
    ],
}


def get_test_event(item):
    for ev in item.get("event", []):
        if ev.get("listen") == "test":
            return ev
    ev = {"listen": "test", "script": {"type": "text/javascript", "exec": []}}
    item.setdefault("event", []).append(ev)
    return ev


def augment(item, kind):
    ev = get_test_event(item)
    exec_lines = ev["script"].setdefault("exec", [])
    if any(MARKER in ln for ln in exec_lines):
        return False  # already augmented
    exec_lines.extend(PROBLEM_ASSERTS[kind])
    return True


def status_in(item, code):
    for ev in item.get("event", []):
        if ev.get("listen") == "test":
            j = "\n".join(ev["script"].get("exec", []))
            if f"status({code})" in j or f"-> {code}" in j or f"-&gt; {code}" in j:
                return True
    return False


def walk(items, on_request):
    for it in items:
        if "item" in it:
            walk(it["item"], on_request)
        else:
            on_request(it)


def process(path, rules):
    """rules: list of (status_code, kind) — augment a request when it asserts that status."""
    with open(path) as f:
        col = json.load(f)
    changed = [0]

    def on_request(it):
        for code, kind in rules:
            if status_in(it, code):
                if augment(it, kind):
                    changed[0] += 1
                    print(f"  + {path}: '{it.get('name','')}' [{kind}]")
                break  # one kind per request

    walk(col["item"], on_request)
    if changed[0]:
        with open(path, "w") as f:
            json.dump(col, f, indent=2)
            f.write("\n")
    print(f"{path}: {changed[0]} request(s) augmented")
    return changed[0]


if __name__ == "__main__":
    total = 0
    # 403 -> ACCESS_DENIED ; 201 -> Location ; 422 -> TAG_VALUE_ILLEGAL
    total += process("catalog-abac-matrix.postman_collection.json", [(403, "ACCESS_DENIED"), (201, "LOCATION")])
    total += process("tag-abac-matrix.postman_collection.json", [(422, "TAG_VALUE_ILLEGAL"), (403, "ACCESS_DENIED"), (201, "LOCATION")])
    total += process("team-abac-matrix.postman_collection.json", [(403, "ACCESS_DENIED")])
    print(f"\nTOTAL augmented: {total}")
    sys.exit(0)
