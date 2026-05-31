plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

description = "Spring Boot auto-configuration starter for OPA ABAC authorization"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
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
    // OncePerRequestFilter; conditions on SecurityFilterChain). The consuming app supplies them at
    // runtime; here they are compile-only so the starter stays usable in a non-web/non-security app
    // (the security beans simply back off via @ConditionalOnClass).
    compileOnly("org.springframework.boot:spring-boot-starter-security")
    compileOnly("org.springframework.boot:spring-boot-starter-web")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
