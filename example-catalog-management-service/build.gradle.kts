plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.openapi.generator)
}

description = "Example: e-commerce product catalog management service (the app we secure)"

// This example app is not published.
tasks.named("bootJar") { enabled = true }
tasks.named("jar") { enabled = false }

val generatedSourcesDir = layout.buildDirectory.dir("generated/openapi")

dependencies {
    // The starter's Spring Data layer: base/secure entities, tags, locking repo + CRUD service.
    // It exposes spring-boot-starter-data-jpa as `api`, so the explicit data-jpa dep below is
    // redundant but kept for clarity that this app is a JPA app in its own right.
    implementation(project(":opa-abac-spring-data"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Actuator — example app only; a curated set of endpoints exposed for local debugging (see application.yml).
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Schema management
    implementation("org.liquibase:liquibase-core")
    runtimeOnly("org.postgresql:postgresql")

    // API docs / Swagger UI
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // Support deps the `spring` generator's interfaceOnly output references
    implementation(libs.swagger.annotations)
    implementation(libs.jakarta.validation.api)
    implementation(libs.jakarta.annotation.api)

    // Integration tests run against real Postgres via Testcontainers.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}

// Resolve a Docker-compatible socket for Testcontainers.
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
    // docker-java (Testcontainers' client) uses deep reflection that the JDK 21 module
    // system blocks by default. Open the packages it needs.
    jvmArgs(
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
    )
}

// --- OpenAPI codegen (vanilla org.openapi.generator plugin) -------------------
openApiGenerate {
    generatorName.set("spring")
    inputSpec.set("$projectDir/src/main/resources/openapi/catalog-api.yaml")
    outputDir.set(generatedSourcesDir.get().asFile.path)
    apiPackage.set("dev.dmitriikonovalov.example.catalog.openapi.api")
    modelPackage.set("dev.dmitriikonovalov.example.catalog.openapi.model")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot3" to "true",
            "useTags" to "true",
            "skipDefaultInterface" to "true",
            "useJakartaEe" to "true",
            "openApiNullable" to "false",
            "useBeanValidation" to "true",
            "documentationProvider" to "none",
            "hideGenerationTimestamp" to "true",
        ),
    )
}

// Wire generated sources into the main source set and make compilation depend on codegen.
sourceSets {
    named("main") {
        java {
            srcDir(generatedSourcesDir.map { it.dir("src/main/java") })
        }
    }
}

tasks.named("compileJava") {
    dependsOn(tasks.named("openApiGenerate"))
}
