plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

description = "Example: MCP server (Spring AI @McpTool catalog proxies behind an OPA tool-gate)"

// This example app is not published.
tasks.named("bootJar") { enabled = true }
tasks.named("jar") { enabled = false }

dependencies {
    // Spring AI's MCP server BOM + starter. The starter's POM declares spring-boot 4.1.0, but the
    // dependency-management plugin imports THIS repo's Boot BOM (the 4.0.x pin in libs.versions.toml)
    // first, so every spring-boot-* artifact resolves to it — the module builds and runs on the repo's
    // baseline (ADR 0026).
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.ai.starter.mcp.server.webmvc)

    // The OPA ABAC starter — consumed exactly as an adopter would. This module uses the library's
    // AbacFilter (bearer -> AbacAuthentication) and, from T3/T4, its OpaClient. No library change.
    //
    // The data-filtering layer is excluded: this service owns NO persistence (its tools proxy the
    // catalog REST API and it holds no rows), and the starter api-exposes opa-abac-spring-data, which
    // api-exposes spring-boot-starter-data-jpa — enough for Boot to auto-configure a DataSource and
    // then fail with "Failed to determine a suitable driver class". Excluding the module lets every
    // JPA auto-configuration back off on an ABSENT classpath rather than being disabled by name; it is
    // the "depend on what you use" path the starter's own build script documents for non-JPA adopters.
    implementation(project(":opa-abac-spring-boot-starter")) {
        exclude(group = "dev.dmitriikonovalov", module = "opa-abac-spring-data")
    }

    // The app declares its own security chain (the starter intentionally does not).
    implementation("org.springframework.boot:spring-boot-starter-security")

    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // Actuator — example app only; health is what deploy.sh waits on.
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Explicit launcher (aligned via the Boot BOM) — Gradle 9 drops auto-loading.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
