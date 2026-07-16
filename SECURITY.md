# Security Policy

`spring-boot-starter-opa-abac` is an **authorization** library — security is its core concern, so
vulnerability reports are taken seriously and handled privately.

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues, discussions, or pull
requests.**

Instead, report privately through either:

- **GitHub Security Advisories** — [open a private report](https://github.com/Void3110/spring-boot-starter-opa-abac/security/advisories/new)
  (Security → Advisories → *Report a vulnerability*). This is the preferred channel.
- **Email** — `void31102025@gmail.com` with the subject prefixed `[SECURITY]`.

Please include:

- the affected version(s) and module(s),
- a description of the issue and its impact (e.g. an authorization bypass, a fail-open path),
- a minimal reproduction or proof of concept if you have one.

You can expect an acknowledgement within a few days. Once the issue is confirmed, a fix and a
coordinated disclosure timeline will be agreed with you before any public release.

## Supported versions

This project follows semantic versioning and publishes to Maven Central under
`dev.dmitriikonovalov`. Security fixes are applied to the **latest released minor line**; please
upgrade to the most recent release before reporting, in case the issue is already fixed.

| Version | Supported |
|---------|-----------|
| Latest `1.x` release | ✅ |
| Older releases | Best-effort |

## Scope

In scope: authorization-bypass and fail-**open** defects in the library modules (`opa-abac-core`,
`opa-abac-spring-security`, `opa-abac-spring-data`, `opa-abac-keycloak-directory`, the starter), the
Rego policy corpus, and anything that causes the library to **allow** a request it should deny.

Out of scope: the `example-*` services and the local demo rig (APISIX/Keycloak/OPA/Postgres compose)
are **demonstration scaffolding**, not a hardened deployment — their gateway does throwaway identity
handling by design. Issues there are welcome as regular bugs but are not treated as library vulnerabilities.

## Design posture

The library is **fail-closed by default**: any error talking to OPA, any timeout, any unparseable
response, or any ambiguity results in a **deny**. A report that the library *fails open* anywhere is
exactly the kind of issue this policy exists to catch.
