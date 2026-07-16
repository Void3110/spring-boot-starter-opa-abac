# Contributing

Thanks for your interest in `spring-boot-starter-opa-abac` — a Spring Boot starter for
**ABAC authorization over Open Policy Agent (OPA)**. Contributions of all sizes are welcome:
bug reports, doc fixes, test cases, and features.

## Ways to contribute

- **Report a bug or request a feature** — open a [GitHub issue](https://github.com/Void3110/spring-boot-starter-opa-abac/issues).
  For bugs, include the library version, JDK/Spring Boot versions, and a minimal reproduction.
- **Improve the docs** — the guides live in [`docs/`](docs/README.md); small fixes are always welcome.
- **Send a pull request** — see the workflow below.

> **Security issues are different — do not open a public issue.** See [SECURITY.md](SECURITY.md).

## Development setup

**Requirements** (see the [README](README.md#requirements) for detail):

- **Java 25+** and **Spring Boot 4.0+**
- A container runtime — **Docker or podman** (for the integration tests and the example rig)
- **OPA 1.x** and **PostgreSQL** are provided by the local rig / Testcontainers — you don't install them separately

**Build and test:**

```bash
./gradlew build          # compiles + runs unit and Testcontainers integration tests
                         # (a real Postgres 16 is spun up automatically)
```

CI (GitHub Actions) runs the same build plus `opa test` on the Rego corpus and a newman gateway
matrix — Docker is provided out of the box there, so no extra config is needed.

The repo is a **monorepo**: the published library modules (`opa-abac-*`) plus two `example-*`
services used as demos and end-to-end fixtures (the examples are **not** published). See
[README → This is a monorepo](README.md#this-is-a-monorepo).

## Pull request workflow

1. **Branch** from `main` (e.g. `feature/short-description`).
2. **Keep changes focused** — one logical change per PR is easier to review.
3. **Add tests.** New behavior needs coverage; the bar is the existing suite (unit + Testcontainers
   ITs, plus `opa test` for any Rego change).
4. **Run `./gradlew build` locally** and make sure it's green before pushing.
5. **Open the PR** against `main` with a clear description of what changed and why. CI must pass.
6. Changes that alter authorization behavior, the Rego corpus, or a public API get a closer review —
   this is a security-sensitive library and the default posture is **fail-closed**.

## Conventions

- **Java 25 / Spring Boot 4** idioms; match the surrounding code's style.
- **Fail-closed by default** — any authorization ambiguity, OPA error, or timeout must **deny**, never allow.
- **Rego** targets OPA 1.x (the `if`/`in`/`contains`/`every` keywords are built in — no imports).
- Keep the example services and the library modules cleanly separated (the examples demonstrate; the
  library is the product).

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), the same license as the project.
