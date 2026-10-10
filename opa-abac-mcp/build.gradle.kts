plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

description = "OPA ABAC tool-gate for MCP servers: agent tool-call authorization (incubating)"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    // AbacContext, OpaClient, RoleDefinition, the "could not decide" family (core) and
    // AbacAuthentication (the caller the starter's filter establishes) — both appear in this module's
    // public signatures.
    api(project(":opa-abac-spring-security"))

    // The MCP Java SDK: its specification records and result types are in public signatures
    // (ToolCallGate.gate(SyncToolSpecification, …), ToolRosterFilter.apply(…, ListToolsResult)), so
    // `api`. An EXPLICIT catalog version, not a BOM-managed one: neither the Boot BOM nor the Spring AI
    // BOM manages it, and the starter's compileOnly edge to this module would otherwise resolve
    // mcp-core with no version at all. No Spring AI artifact is a dependency of this module (ADR 0036 §13).
    api(libs.mcp.core)

    // Declared HERE, not inherited: opa-abac-spring-security declares web + security `implementation`,
    // so neither reaches this module's compile classpath transitively. RequestContextHolder (the
    // turn-scoped memos) and SecurityContextHolder (the caller) are implementation details.
    implementation("org.springframework:spring-web")
    implementation("org.springframework.security:spring-security-core")
    implementation(libs.slf4j.api)

    // @ConfigurationProperties binding metadata only — the starter owns the wiring (no beans here).
    implementation("org.springframework.boot:spring-boot")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Explicit launcher (aligned with the engine via the BOM) — Gradle 9 drops auto-loading.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj.core)
    // MockHttpServletRequest + RequestContextHolder binding for the turn-scoped memo tests.
    testImplementation("org.springframework:spring-test")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    // logback on the test COMPILE path too: the agent-clients guard asserts its own WARN (a ListAppender).
    testImplementation("ch.qos.logback:logback-classic")
}
