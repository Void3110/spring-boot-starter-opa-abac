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

// --- Mutation testing (PIT) — REPORTING ONLY --------------------------------------------------
// ENGINEERING-BACKLOG item 1. Answers the one question line coverage cannot: does a test actually
// FAIL when the code it guards is broken? The defect that motivated this survived a full adversarial
// review round — a test that asserted on its own input rather than on what the code returned, so the
// broken implementation its own comment named would have passed. A reviewer caught it by mutating
// the production code by hand; PIT automates exactly that.
//
// Wired as a plain JavaExec against PIT's command-line entry point rather than through the
// `info.solidsoft.pitest` plugin: that plugin last shipped 2023-09 and fails to apply on this repo's
// Gradle 9 wrapper (it reads `ReportingExtension.baseDir`, removed in Gradle 9). PIT's own CLI is
// stable and lets the engine track its current line, which is what reads Java 25 class files.
//
// Deliberately NO threshold anywhere: the first job is a baseline. A noisy gate on day one gets
// ignored, which is worse than no gate. Gating comes later, and starts with the authorization
// surfaces (`opa-abac-core`, `opa-abac-spring-security`) — a surviving mutant there is worth more
// than one in a DTO.
//
//   ./gradlew mutationTest                          # every wired module
//   ./gradlew :opa-abac-core:mutationTest           # one module
//   open <module>/build/reports/pitest/index.html   # the survivors, annotated on the source
val mutationTargets = mapOf(
    "opa-abac-core" to "dev.dmitriikonovalov.opaabac.core.*",
    "opa-abac-spring-security" to "dev.dmitriikonovalov.opaabac.security.*",
    "opa-abac-spring-data" to "dev.dmitriikonovalov.opaabac.data.*",
    "opa-abac-keycloak-directory" to "dev.dmitriikonovalov.opaabac.keycloak.directory.*",
    "opa-abac-spring-boot-starter" to "dev.dmitriikonovalov.opaabac.autoconfigure.*",
    "example-catalog-management-service" to "dev.dmitriikonovalov.example.catalog.*",
)

// Suites that mix Docker-backed `*IT` classes with plain unit tests. PIT re-runs the selected tests
// once per mutant, so leaving a Testcontainers IT in the loop turns a one-minute analysis into an
// unusable one. Restricting to `*Test` keeps the run interactive — at the cost that a class
// exercised ONLY by an IT reports as NO_COVERAGE rather than as genuinely untested. In these two
// modules read SURVIVED (a test ran and did not notice) as the signal, not NO_COVERAGE.
val mutationUnitTestOnlyModules = setOf(
    "opa-abac-spring-data",
    "example-catalog-management-service",
)

// OpenAPI codegen lands inside the app's own package tree, so the target glob would otherwise sweep
// it in. Generated code is not ours to test.
val mutationExcludedClasses = mapOf(
    "example-catalog-management-service" to "dev.dmitriikonovalov.example.catalog.openapi.*",
)

configure(subprojects.filter { it.name in mutationTargets.keys }) {
    // `java` is applied to every non-BOM subproject above; `plugins.withId` fires exactly once and
    // is order-safe, so this works whether the module adds `java-library` or the Boot plugin later.
    plugins.withId("java") {
        val targetClasses = mutationTargets.getValue(name)
        val targetTests =
            if (name in mutationUnitTestOnlyModules) "${targetClasses}Test" else targetClasses
        val excludedClasses = mutationExcludedClasses[name]

        // PIT's own runtime, in its own configuration so it can never leak into a published POM.
        val pitestRuntime = configurations.create("pitest") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies.add(pitestRuntime.name, rootProject.libs.pitest.command.line)
        dependencies.add(pitestRuntime.name, rootProject.libs.pitest.junit5.plugin)

        val sourceSets = extensions.getByType<SourceSetContainer>()
        val mainSourceSet = sourceSets.getByName("main")
        val testSourceSet = sourceSets.getByName("test")
        val reportDir = layout.buildDirectory.dir("reports/pitest")

        // Resolved here, at PROJECT scope. Inside the `tasks.register<JavaExec>` lambda the implicit
        // receiver is the task, whose `extensions` container holds only ExtraProperties.
        val toolchains = extensions.getByType<JavaToolchainService>()

        // PIT forks a minion JVM and hands it `--classPath` verbatim, so the launcher classpath and
        // the analysed classpath must both carry the engine, the code and the tests. Lazy — nothing
        // resolves until the task runs.
        //
        // ORDER IS LOAD-BEARING: `pitestRuntime` must come FIRST. PIT reads class files with a plain,
        // unshaded `org.objectweb.asm.ClassReader`, and Boot's test starter drags ASM 9.7.1 onto the
        // test runtime classpath (spring-boot-starter-test -> json-path -> json-smart ->
        // accessors-smart). 9.7.1 reads class files only up to Java 24, so whenever it won the
        // classpath race PIT died on our own Java 25 output with "Unsupported class file major
        // version 69". PIT's own ASM is 9.10.1; putting its configuration first lets that one win.
        val analysedClasspath = files(
            pitestRuntime,
            mainSourceSet.output,
            testSourceSet.output,
            testSourceSet.runtimeClasspath,
        )
        val mutableCodePaths = mainSourceSet.output.classesDirs
        val sourceDirs = mainSourceSet.java.srcDirs

        tasks.register<JavaExec>("mutationTest") {
            group = "verification"
            description = "PIT mutation testing (report only, no threshold) over $targetClasses"
            dependsOn(tasks.named("testClasses"))

            classpath = analysedClasspath
            mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
            javaLauncher.set(
                toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
            )

            argumentProviders.add(
                CommandLineArgumentProvider {
                    buildList {
                        add("--classPath"); add(analysedClasspath.joinToString(","))
                        add("--targetClasses"); add(targetClasses)
                        add("--targetTests"); add(targetTests)
                        // Mutate only this module's own output — never a dependency that happens to
                        // share the package prefix.
                        add("--mutableCodePaths"); add(mutableCodePaths.joinToString(","))
                        add("--sourceDirs"); add(sourceDirs.joinToString(","))
                        add("--reportDir"); add(reportDir.get().asFile.absolutePath)
                        add("--outputFormats"); add("HTML,XML")
                        add("--timestampedReports"); add("false")
                        add("--threads"); add(Runtime.getRuntime().availableProcessors().toString())
                        // Baseline mode: a module with nothing mutable must not fail the build, and
                        // no mutation score is enforced yet.
                        add("--failWhenNoMutations"); add("false")
                        if (excludedClasses != null) {
                            add("--excludedClasses"); add(excludedClasses)
                        }
                    }
                }
            )
        }
    }
}

// Aggregate entry point: `./gradlew mutationTest` runs every wired module.
tasks.register("mutationTest") {
    group = "verification"
    description = "Runs PIT mutation testing across every wired module (report only)"
    dependsOn(mutationTargets.keys.map { ":$it:mutationTest" })
}
