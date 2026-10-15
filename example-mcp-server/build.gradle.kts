plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

description = "Example: MCP server (Spring AI @McpTool catalog proxies behind an OPA tool-gate)"

// The Boot BOM, imported EXPLICITLY although the Boot plugin already imports its own (4.0.8, the plugin
// version): with -PspringBootLine=compat this later import wins and the example's tests run on the newest
// 4.1.x (the CI step, ADR 0036 §10); without it, it is the same 4.0.x pin — resolution unchanged.
dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.extra["springBootBomVersion"]}")
    }
}

// This example app is not published.
tasks.named("bootJar") { enabled = true }
tasks.named("jar") { enabled = false }

dependencies {
    // The tool-gate library module (ADR 0036), consumed exactly as an adopter would: the starter
    // auto-configures the gate on every tool specification; the example keeps only its catalog-specific
    // pieces — its tools and declarations, its ceiling source, and the reflective roster installer.
    implementation(project(":opa-abac-mcp"))

    // Spring AI's MCP server BOM + starter. The starter's POM declares spring-boot 4.1.0, but the
    // dependency-management plugin imports THIS repo's Boot BOM (the 4.0.x pin in libs.versions.toml)
    // first, so every spring-boot-* artifact resolves to it — the module builds and runs on the repo's
    // baseline (ADR 0026).
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.ai.starter.mcp.server.webmvc)

    // The OPA ABAC starter — consumed exactly as an adopter would: the AbacFilter (bearer ->
    // AbacAuthentication), the OpaClient, and OpaMcpAutoConfiguration (the tool-gate's wiring).
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
