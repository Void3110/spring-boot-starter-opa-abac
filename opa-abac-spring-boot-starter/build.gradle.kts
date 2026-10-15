plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

description = "Spring Boot auto-configuration starter for OPA ABAC authorization"

dependencyManagement {
    imports {
        // The repo's Boot line, or the newest 4.1.x under -PspringBootLine=compat (root build script).
        mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.extra["springBootBomVersion"]}")
    }
}

// The published starter: pulls in the integration modules and provides the auto-configuration.
dependencies {
    api(project(":opa-abac-core"))
    api(project(":opa-abac-spring-security"))
    api(project(":opa-abac-spring-data"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    // Security + web types are referenced by the security auto-config (AbacFilter extends
    // OncePerRequestFilter; conditions on SecurityFilterChain), so they are compile-only HERE.
    // Honest caveat: opa-abac-spring-security declares them `implementation`, and the starter
    // api-depends on that module — so a starter consumer still gets web+security on the runtime
    // classpath transitively, and the @ConditionalOnClass back-off only fires if the consumer
    // explicitly excludes them. A non-web/non-security app should depend on opa-abac-core (and
    // opa-abac-spring-data if filtering) directly instead of the starter. Restructuring the module
    // scopes so the starter is truly modular is a tracked follow-up decision (see
    // docs/code-review/FULL-REPO-REVIEW-2026-06-10.md), not something to half-change here.
    compileOnly("org.springframework.boot:spring-boot-starter-security")
    compileOnly("org.springframework.boot:spring-boot-starter-webmvc")

    // Resilience4j (Slice B3): the OPA resilience decorator is auto-configured @ConditionalOnClass R4j, the
    // standard "optional integration" pattern (ADR 0017 §6). R4j is NOT declared here — it arrives
    // transitively as `api` from opa-abac-spring-security (which needs it for the CallGuard impl), the same
    // honest caveat as web/security above: the @ConditionalOnClass back-off only fires if a consumer
    // explicitly excludes R4j. An adopter who wants the plain client excludes io.github.resilience4j or sets
    // opa.abac.resilience.enabled=false (the kill-switch → byte-identical to pre-B3). The U8 slice test
    // proves both classpath states via FilteredClassLoader.

    // Keycloak user-directory (ADR 0020 §3): the optional module is compileOnly so the directory
    // auto-config can construct KeycloakUserDirectory when an adopter adds the module, WITHOUT dragging
    // Keycloak onto every adopter — the same treatment as web/security above. The @ConditionalOnClass
    // keys off the admin-client type by name; a bare adopter gets the NoOpUserDirectory fallback.
    compileOnly(project(":opa-abac-keycloak-directory"))

    // The MCP tool-gate (ADR 0036): the same optional-module treatment. compileOnly, so the starter drags
    // neither the module nor the MCP SDK onto every adopter; OpaMcpAutoConfiguration activates by name when
    // the module AND Spring AI's MCP server are both on the classpath. The configuration references no
    // Spring AI type (the condition is a name string), so Spring AI is not even compileOnly here. mcp-core
    // resolves through this edge at the module's explicit catalog version (the Boot BOM does not manage it).
    compileOnly(project(":opa-abac-mcp"))

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":opa-abac-keycloak-directory"))
    testImplementation(project(":opa-abac-mcp"))
    // The real Spring AI MCP server starter on the TEST classpath only: the context-runner tests prove the
    // @ConditionalOnClass activation against the actual class name, and T5's per-type ITs run a real server.
    testImplementation(platform(libs.spring.ai.bom))
    testImplementation(libs.spring.ai.starter.mcp.server.webmvc)
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    // spring-webflux (Boot-managed) for U24 only: Boot's REACTIVE condition matches only when
    // org.springframework.web.reactive.HandlerResult is loadable, so the reactive-guard test needs it. It is
    // added on CI (GitHub Actions sets CI) and with -PwithWebflux — NOT unconditionally, so the default build
    // never REQUIRES an artifact a host without network access to Maven Central cannot resolve.
    // OpaMcpReactiveGuardTest skips itself without it, and fails (never skips) on CI.
    if (providers.environmentVariable("CI").isPresent || providers.gradleProperty("withWebflux").isPresent) {
        testImplementation("org.springframework:spring-webflux")
    }
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Explicit launcher (aligned with the engine via the BOM) — Gradle 9 drops auto-loading.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj.core)
}
