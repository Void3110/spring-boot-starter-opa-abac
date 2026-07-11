plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

description = "Spring Security integration for OPA ABAC authorization"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    api(project(":opa-abac-core"))

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Resilience4j backs the CallGuard seam (Slice B3 cross-service HTTP resilience). Exposed as `api`
    // so the OPA decorator's auto-config (starter) and the example app's resolve/tag wrappers can build
    // CallGuards from the same backend. The CallGuard *interface* itself is R4j-free — the Boot-4 native
    // backend is a later one-impl swap (ADR 0017 §7). Versions resolved by the Spring Boot BOM above.
    api(libs.resilience4j.circuitbreaker)
    api(libs.resilience4j.core)

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Explicit launcher (aligned with the engine via the BOM) — Gradle 9 drops auto-loading.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.mockito.core)
    testImplementation(libs.assertj.core)
}
