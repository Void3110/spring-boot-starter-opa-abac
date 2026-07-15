// Root build script.
//
// Topology: per-module plugins + a shared version catalog (gradle/libs.versions.toml).
//   - Library modules (opa-abac-*) apply only `java-library`.
//   - Example apps apply the Spring Boot + dependency-management + openapi-generator
//     plugins themselves, so the libraries never inherit `bootJar`/`bootRun`.
//
// This root file only configures what is genuinely common to EVERY subproject:
// group/version coordinates, repositories, the Java toolchain, and the test platform.

import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    // Declared (not applied) at the root so any module can opt in without re-stating versions.
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.openapi.generator) apply false
    // Central-Portal publishing (ADR 0027). Declared here, applied module-by-module below to an
    // EXPLICIT library allow-list — never a blanket subprojects{} (that would publish the example
    // apps, with their demo fixtures, to Central: the fail-closed edge of this slice).
    alias(libs.plugins.maven.publish) apply false
    // SonarQube analysis (registers the root `sonar` task) for the LOCAL pre-push gate — driven by
    // .sonar-local/sonar-local.sh; host/token/projectKey are passed on that command line, so a bare
    // `./gradlew sonar` without them is not a supported entry point.
    alias(libs.plugins.sonarqube)
}

allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}

// --- Publishing: the explicit library allow-list ---------------------------------------------
// The five publishable library modules. The BOM (opa-abac-bom) applies the plugin in its own
// build with the JavaPlatform variant, so it is intentionally NOT in this jar-producing list.
// NEVER add an example-* module here.
val publishableJarModules = setOf(
    "opa-abac-core",
    "opa-abac-spring-security",
    "opa-abac-spring-data",
    "opa-abac-keycloak-directory",
    "opa-abac-spring-boot-starter",
)

configure(subprojects.filter { it.name in publishableJarModules }) {
    apply(plugin = "com.vanniktech.maven.publish")

    // Defer the JavaLibrary wiring until the module has applied `java-library` in its own build
    // script. The root `configure(subprojects…)` block evaluates before the module scripts, so
    // configure(JavaLibrary(...)) here (which requires java-library present) must wait for the
    // plugin to arrive — plugins.withId fires exactly once, order-safe.
    plugins.withId("java-library") {
        // Reads GROUP / VERSION_NAME / the shared POM_* block from the root gradle.properties and
        // the per-module POM_NAME / POM_DESCRIPTION / POM_ARTIFACT_ID (added per module in T2).
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()  // Central Portal is the default host (no SonatypeHost arg)
            signAllPublications()    // GPG signing; keys come from ~/.gradle only (never the repo)
            configure(
                JavaLibrary(
                    javadocJar = JavadocJar.Javadoc(),
                    sourcesJar = true,
                )
            )
        }

        // The javadoc jar must build on the un-linted public surface (missing/malformed tags must
        // not fail the release build). -Xdoclint:none is the whole mitigation — javadoc PROSE is
        // out of scope (ADR 0027 / T4). Configured here so it travels with the publish wiring.
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
        }
    }
}

// The BOM is a `java-platform` — it has no source, no toolchain, and no tests, and `java-platform`
// cannot coexist with `java`. So the shared `java` config below applies to every subproject EXCEPT
// the BOM (which configures its own `java-platform` + publish wiring in its module build).
val bomModules = setOf("opa-abac-bom")

configure(subprojects.filter { it.name !in bomModules }) {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        // Surface test execution in the build output (local and CI). The default is
        // near-silent — only failures. Showing passed/skipped/failed makes the
        // Postgres-backed integration tests visible when they run (e.g. on CI).
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}

// The BOM still needs a repository for resolving the plugin marker; give it just that.
configure(subprojects.filter { it.name in bomModules }) {
    repositories {
        mavenCentral()
    }
}

// --- SonarQube (the local pre-push gate; see .sonar-local/README.md) --------------------------
// Analysis covers every JVM module (libraries + example apps); the BOM has no source and
// contributes nothing. Generated code (OpenAPI codegen, annotation-processor output) lives under
// build/ and is excluded — findings should only ever point at hand-written code.
sonar {
    properties {
        property("sonar.exclusions", "**/build/**,**/bin/**")
        property("sonar.coverage.exclusions", "**/build/**,**/bin/**")
    }
}
