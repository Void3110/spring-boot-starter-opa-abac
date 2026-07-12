plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

description = "Keycloak-admin implementation of the OPA ABAC user-directory port (optional module)"

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    // Security constraint: force the transitive commons-io off the vulnerable 2.11.0 (CVE-2024-47554,
    // XmlStreamReader DoS — fixed in 2.14.0) that keycloak-admin-client 26.x drags via
    // resteasy-multipart-provider → apache-mime4j. The Boot 4 BOM does not manage commons-io, so
    // without this the runtimeClasspath resolves 2.11.0. Defence-in-depth: the XML path is not reachable
    // via KeycloakUserDirectory (JSON-only, fail-closed), but an adopter enabling this module must not
    // inherit a known-vuln transitive. See the 7.4 security review (F2).
    constraints {
        implementation(libs.commons.io) {
            because("CVE-2024-47554: keycloak-admin-client 26.x pulls commons-io 2.11.0 (< 2.14.0)")
        }
    }

    // The port this module implements (UserDirectory / DirectoryUser).
    api(project(":opa-abac-spring-security"))

    // The official admin client (ADR 0020 §8). `implementation`: no Keycloak type appears in this
    // module's public API — server URL / realm / credentials stay behind KeycloakDirectoryProperties
    // (the URL-encapsulation fork, §6).
    implementation(libs.keycloak.admin.client)
    implementation(libs.slf4j.api)

    // @ConfigurationProperties binding metadata only — the starter owns the wiring (no beans here).
    implementation("org.springframework.boot:spring-boot")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Explicit launcher (aligned with the engine via the BOM) — Gradle 9 drops auto-loading.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.assertj.core)
    // Real logging backend so the fail-closed tests can assert the DISTINCT WARN per error edge (I2b).
    testImplementation("ch.qos.logback:logback-classic")
}
