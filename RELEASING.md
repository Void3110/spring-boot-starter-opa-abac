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

Publish the **public** half to the keyservers Central checks, so it can verify the signatures:

```bash
# Upload via HTTP — more reliable than `gpg --send-keys`, which can fail with
# "keyserver send failed: Invalid argument" on some GnuPG 2.x builds.
gpg --armor --export <YOUR_LONG_KEY_ID> > /tmp/pub.asc
curl --data-urlencode "keytext@/tmp/pub.asc" https://keyserver.ubuntu.com/pks/add

# keys.openpgp.org (Central's preferred server) needs a JSON upload + EMAIL VERIFICATION
# before it serves the UID — upload, then click the link it emails you:
curl -X POST https://keys.openpgp.org/vks/v1/upload \
     -H 'Content-Type: application/json' \
     -d "{\"keytext\": $(python3 -c 'import json,sys; print(json.dumps(open("/tmp/pub.asc").read()))')}"
# → open the verification email to void…@… and click the link, so the email UID is published.
```

**Confirm the key is actually queryable before you publish** — Central rejects with *"Could not find a
public key by the key fingerprint"* if it isn't. Both of these must return the key:

```bash
FPR=<YOUR_FULL_40-HEX_FINGERPRINT>   # gpg --fingerprint shows it
curl -s "https://keyserver.ubuntu.com/pks/lookup?op=index&search=0x$FPR&options=mr" | grep pub
curl -s "https://keys.openpgp.org/pks/lookup?op=index&search=0x$FPR&options=mr"      | grep pub
```

Keyserver indexing can lag a minute or two after upload — poll until both `op=index` queries resolve.

### 2b. Get a Central Portal user token

Central Portal → **Account → Generate User Token**. This yields a **username/password pair** (a token,
*not* your login) used by the publish task. The password is shown **once** — copy it immediately.

### 2c. Put it in `~/.gradle/gradle.properties` (never the repo)

```properties
# ~/.gradle/gradle.properties  — user-home only, NEVER committed
mavenCentralUsername=<central-portal-token-username>
mavenCentralPassword=<central-portal-token-password>

# GPG signing via the gpg BINARY (see the critical note below — required, not optional).
signing.gnupg.keyName=<YOUR_LONG_KEY_ID>
signing.gnupg.passphrase=<your gpg passphrase>
signing.gnupg.executable=/opt/homebrew/bin/gpg     # absolute path; Gradle otherwise looks for `gpg2`
```

> **⚠️ CRITICAL — you MUST sign with the gpg binary, not the vanniktech in-memory key.** The plugin's
> default `signingInMemoryKey` path (Bouncy Castle) produces v4 signatures that carry only the 64-bit
> *issuer key ID* (subpacket 16) and **omit the issuer-fingerprint subpacket (33)**. Central's validator
> resolves keys by **full fingerprint**, so those signatures fail with *"Could not find a public key by
> the key fingerprint"* even when the key is correctly on the keyservers. The local `gpg` binary emits
> subpkt 33, so signing via it is what Central accepts. Because the repo's `build.gradle.kts` uses
> vanniktech's `signAllPublications()` (in-memory by default), force the gpg command at **invocation
> time** with an init script (no repo change) — see §4. Verify a produced signature has it:
> `gpg --list-packets ~/.m2/…/opa-abac-core-1.0.0.pom.asc | grep 'issuer fpr'`.

> Enable loopback pinentry so gpg can take the passphrase from the property non-interactively:
> `echo allow-loopback-pinentry >> ~/.gnupg/gpg-agent.conf && gpgconf --reload gpg-agent`.

---

## 3. Dry-run locally (prove the artifacts before you publish)

First, create the init script that forces gpg-command signing (per the §2c critical note). Save it as
`/tmp/use-gpg-cmd.init.gradle.kts`:

```kotlin
// Force Gradle's signing plugin to sign via the gpg binary (emits issuer-fingerprint subpkt 33
// that Central requires). Invocation-time only — no repo change.
allprojects {
    plugins.withId("signing") {
        extensions.configure<SigningExtension>("signing") { useGpgCmd() }
    }
}
```

Then verify the full six-coordinate signed set builds **into your local Maven repo**, without touching Central:

```bash
./gradlew clean build                                              # green baseline
./gradlew publishToMavenLocal -I /tmp/use-gpg-cmd.init.gradle.kts  # signs → ~/.m2/repository/dev/dmitriikonovalov/…

# CONFIRM the signature carries the issuer fingerprint (subpkt 33) — the thing Central checks:
gpg --list-packets ~/.m2/repository/dev/dmitriikonovalov/opa-abac-core/1.0.0/opa-abac-core-1.0.0.pom.asc \
  | grep 'issuer fpr'    # must print: hashed subpkt 33 … (issuer fpr v4 <YOUR_FINGERPRINT>)
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

With the namespace **Verified** (§1), the key on both keyservers (§2a), and credentials in place (§2):

```bash
./gradlew publishAndReleaseToMavenCentral -I /tmp/use-gpg-cmd.init.gradle.kts --no-configuration-cache
```

The `-I` init script is **required** (gpg-command signing — see §2c); without it the deployment fails
validation with *"Could not find a public key by the key fingerprint."* This uploads all six signed
coordinates to the Central Portal and (because the wiring uses the automatic-release path) triggers
validation and release — a successful run ends with **"Deployment is being published to Maven Central."**
Watch the **Deployments** tab on the Portal; a failed deployment leaves a droppable FAILED entry and
releases nothing (safe to retry).

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
