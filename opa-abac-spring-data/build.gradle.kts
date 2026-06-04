plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

description = "Spring Data JPA integration for OPA partial-evaluation filtering"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    api(project(":opa-abac-core"))

    // `api`, not `implementation`: the base entities and CRUD service expose JPA/Spring Data types
    // (jakarta.persistence, JpaRepository, AbacDataObject), so consumers inherit JPA on their compile
    // classpath. Jackson (used by ResourceTagsConverter) comes transitively via this starter.
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)

    // The ResidualSpecificationFactory Testcontainers IT runs the generated Specification against a real
    // Postgres + JSONB (never H2 — the jsonb_* functions and the `?` operator are Postgres-specific).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.postgresql:postgresql")
}

// Resolve a Docker-compatible socket for Testcontainers (the ResidualSpecificationFactory IT).
//   1. An explicit DOCKER_HOST in the environment wins.
//   2. Otherwise, if podman is installed and its machine is running, use its API socket.
//   3. Otherwise leave it unset — Testcontainers auto-detects (Docker Desktop, CI, etc.).
fun resolveDockerHost(): String? {
    System.getenv("DOCKER_HOST")?.takeIf { it.isNotBlank() }?.let { return it }
    return runCatching {
        val proc = ProcessBuilder(
            "podman", "machine", "inspect", "podman-machine-default",
            "--format", "{{.ConnectionInfo.PodmanSocket.Path}}",
        ).redirectErrorStream(true).start()
        val path = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (proc.exitValue() == 0 && path.isNotBlank() && file(path).exists()) "unix://$path" else null
    }.getOrNull()
}

tasks.named<Test>("test") {
    // Skip the privileged Ryuk reaper (podman blocks it); harmless under real Docker.
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    resolveDockerHost()?.let { environment("DOCKER_HOST", it) }
}
