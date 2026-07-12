# Releasing to Maven Central

This is the **maintainer runbook** for publishing the `opa-abac-*` library modules to
[Maven Central](https://central.sonatype.com) via the
[Central Publisher Portal](https://central.sonatype.org/publish/publish-portal-guide/).

The build wiring is already in place (see [ADR&nbsp;0027](docs/architecture/adr/0027-maven-central-release-engineering.md)):
the [`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/) plugin
is applied to the five library modules **and** the `opa-abac-bom` platform (never the `example-*` apps),
signing is wired into every publish task, and the POM metadata is complete. What remains — namespace
verification, key custody, and pressing **Publish** — is **out-of-band** and manual, by design: it needs
credentials and a DNS record that must never live in the repo.

Six coordinates are published, all under **`dev.dmitriikonovalov`**:

```
dev.dmitriikonovalov:opa-abac-core:<version>
dev.dmitriikonovalov:opa-abac-spring-security:<version>
dev.dmitriikonovalov:opa-abac-spring-data:<version>
dev.dmitriikonovalov:opa-abac-keycloak-directory:<version>
dev.dmitriikonovalov:opa-abac-spring-boot-starter:<version>
dev.dmitriikonovalov:opa-abac-bom:<version>          # java-platform (POM only)
```

> **Everything below happens on the maintainer's machine.** No key, passphrase, token, or DNS value ever
> belongs in a committed file — the broad `.gitignore` key globs (`*.gpg *.key *.p12 *.jks *.keystore …`)
> are the fail-closed net, but the real material lives only in `~/.gnupg` and `~/.gradle/gradle.properties`.

---

## 1. One-time: verify the `dev.dmitriikonovalov` namespace (DNS TXT)

You only do this once per namespace (already **done** for `dev.dmitriikonovalov`; repeat only if you
re-register or the verification lapses).

1. Sign in to the **[Central Portal](https://central.sonatype.com)** (GitHub sign-in works).
2. **Namespaces → Register New Namespace →** enter `dev.dmitriikonovalov`.
3. The Portal shows a **verification token** (a short alphanumeric string, e.g. `abc123def456`) and asks
   you to publish it as a **TXT record on the apex domain** `dmitriikonovalov.dev`.
4. In your DNS host (the domain is on **reg.ru**: `ns1.reg.ru` / `ns2.reg.ru`), add a resource record:
   - **Type:** `TXT`
   - **Subdomain / host:** `@` &nbsp;— reg.ru's convention for the apex (`dmitriikonovalov.dev` itself).
     **Do not** type a subdomain or the full domain name; `@` means "the domain itself." An empty host is
     rejected by reg.ru's dialog, so `@` is the correct entry.
   - **Text / value:** the verification token from step 3, verbatim (ASCII, no quotes).
5. Save the record. Wait for propagation (~1–2&nbsp;min on reg.ru), then confirm from a shell before
   clicking Verify:
   ```bash
   # authoritative (fastest to update) — then public resolvers (what the Portal checks)
   dig +short TXT dmitriikonovalov.dev @ns1.reg.ru
   dig +short TXT dmitriikonovalov.dev @8.8.8.8
   dig +short TXT dmitriikonovalov.dev @1.1.1.1
   # each must print your token, e.g.  "abc123def456"
   ```
6. Once all resolvers return the token, click **Verify** on the Portal. The namespace flips to
   **Verified**.

> **Leave the TXT record in place** afterwards — re-verification is occasionally required and the record
> costs nothing. Removing it is safe but means re-adding it if you ever re-verify.

---

## 2. One-time: GPG signing key + Central token

### 2a. Generate a GPG key (if you don't have one)

```bash
gpg --full-generate-key          # choose RSA 4096; set a passphrase; note the long key id
gpg --list-secret-keys --keyid-format=long    # copy the 16-hex key id
```

Publish the **public** half to a keyserver so Central can verify the signatures:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <YOUR_LONG_KEY_ID>
# (keys.openpgp.org is a common second mirror)
```

Export the **private** key in ASCII-armored form for the in-memory signing property:

```bash
gpg --armor --export-secret-keys <YOUR_LONG_KEY_ID> > /tmp/signing-key.asc   # delete after step 2c
```

### 2b. Get a Central Portal user token

Central Portal → **Account → Generate User Token**. This yields a **username/password pair** (a token,
*not* your login) used by the publish task.

### 2c. Put it all in `~/.gradle/gradle.properties` (never the repo)

```properties
# ~/.gradle/gradle.properties  — user-home only, NEVER committed
mavenCentralUsername=<central-portal-token-username>
mavenCentralPassword=<central-portal-token-password>

# In-memory GPG signing. The key is the ASCII-armored private key with real newlines
# replaced by \n on a single line, OR use the gnupg-agent variant (below).
signingInMemoryKey=<paste the contents of /tmp/signing-key.asc, newlines as \n>
signingInMemoryKeyId=<last 8 hex of the key id>          # optional; required only for subkeys
signingInMemoryKeyPassword=<your gpg passphrase>
```

> Alternatively, to sign via the local gpg agent instead of an in-memory key, drop the three
> `signingInMemory*` lines and set `signing.gnupg.keyName=<KEY_ID>` (+ `signing.gnupg.passphrase`),
> which uses `gpg` on your `PATH`. Either path signs every publication.

Then **delete `/tmp/signing-key.asc`** — it has served its purpose.

---

## 3. Dry-run locally (prove the artifacts before you publish)

Verify the full six-coordinate signed set builds **into your local Maven repo**, without touching Central:

```bash
./gradlew clean build                 # green baseline
./gradlew publishToMavenLocal         # signs + writes to ~/.m2/repository/dev/dmitriikonovalov/…
```

Inspect `~/.m2/repository/dev/dmitriikonovalov/`:

- Each of the **5 libraries** →  `…-<version>.jar`, `…-<version>-sources.jar`, `…-<version>-javadoc.jar`,
  `…-<version>.pom`, and a `.asc` signature next to each.
- **`opa-abac-bom`** →  a `.pom` (with `<packaging>pom</packaging>` and `<dependencyManagement>` listing
  all five modules) + its `.asc`; **no jar**.
- The **`example-*` apps** publish **nothing** (they have no publish tasks).

```bash
# quick check that the examples produced nothing under the namespace:
find ~/.m2/repository/dev/dmitriikonovalov -maxdepth 1 -type d
# → exactly the 6 opa-abac-* coordinates, no example-* anywhere
```

If signing is not configured, the publish task **fails at signing** — it never emits an unsigned or
partial artifact. That is the intended fail-closed behavior.

---

## 4. Publish to Maven Central

With the namespace **Verified** (§1) and credentials in place (§2):

```bash
./gradlew publishAndReleaseToMavenCentral
```

This uploads all six signed coordinates to the Central Portal and (because the wiring uses the
automatic-release path) triggers validation and release. Watch the **Deployments** tab on the Portal;
if it lands in a **Validated / pending** state instead, press **Publish** there to finalize.

Within ~10–30&nbsp;min the coordinates appear on `https://central.sonatype.com`; searchability on
`https://search.maven.org` / `mvnrepository.com` can take a few hours longer.

---

## 5. Post-release: bump the snapshot + tag

Only **after** a successful publish:

```bash
# 1) tag the released commit
git tag v<version>            # e.g. v1.0.0
git push origin v<version>

# 2) open the next development version — edit gradle.properties:
#    VERSION_NAME=<next>-SNAPSHOT      e.g. 1.1.0-SNAPSHOT
git commit -am "build: open <next>-SNAPSHOT development version"
```

> The repo ships with `VERSION_NAME=1.0.0` (a release version, not a snapshot) so the §3 dry-run verifies
> real release coordinates. The `-SNAPSHOT` bump is this **post-publish** step — do not commit it earlier.

---

## Checklist

- [ ] Namespace `dev.dmitriikonovalov` shows **Verified** on the Portal (§1)
- [ ] GPG public key uploaded to a keyserver; private key + token in `~/.gradle/gradle.properties` (§2)
- [ ] `./gradlew publishToMavenLocal` yields 6 signed, POM-complete coordinates; examples none (§3)
- [ ] `./gradlew publishAndReleaseToMavenCentral` succeeds; Portal shows the deployment (§4)
- [ ] `v<version>` tagged; `main` bumped to the next `-SNAPSHOT` (§5)
