plugins {
    `java-library`
}

description = "Framework-agnostic ABAC model and OPA client"

dependencies {
    api(libs.jackson.databind)
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Explicit launcher (aligned with the engine via the BOM) — Gradle 9 drops auto-loading.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.mockito.core)
    testImplementation(libs.assertj.core)
}
