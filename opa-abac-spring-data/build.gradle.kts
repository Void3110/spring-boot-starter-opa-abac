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
    // Hibernate 7.2's JSON FormatMapper (required at boot once any @JdbcTypeCode(JSON) attribute
    // exists) only detects Jackson 2 — it does not support tools.jackson yet, which is why Boot 4's
    // BOM still manages the jackson-2 line. Runtime-only: no com.fasterxml databind type appears in
    // any source; drop this when Hibernate ORM gains Jackson 3 support.
    runtimeOnly("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Explicit launcher (aligned with the engine via the BOM) — Gradle 9 drops auto-loading.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)

    // The ResidualSpecificationFactory Testcontainers IT runs the generated Specification against a real
    // Postgres + JSONB (never H2 — the jsonb_* functions and the `?` operator are Postgres-specific).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 modularized the test slices: @DataJpaTest / @AutoConfigureTestDatabase / TestEntityManager
    // live in technology-specific test artifacts now (aggregated by this starter).
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // The AncestorResolver IT uses PGSimpleDataSource directly (pure-JDBC ltree / recursive-CTE reads),
    // so the driver must be on the test compile classpath, not only at runtime.
    testImplementation("org.postgresql:postgresql")
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
