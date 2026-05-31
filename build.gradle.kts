// Root build script.
//
// Topology: per-module plugins + a shared version catalog (gradle/libs.versions.toml).
//   - Library modules (opa-abac-*) apply only `java-library`.
//   - Example apps apply the Spring Boot + dependency-management + openapi-generator
//     plugins themselves, so the libraries never inherit `bootJar`/`bootRun`.
//
// This root file only configures what is genuinely common to EVERY subproject:
// group/version coordinates, repositories, the Java toolchain, and the test platform.

plugins {
    // Declared (not applied) at the root so any module can opt in without re-stating versions.
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.openapi.generator) apply false
}

allprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
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
