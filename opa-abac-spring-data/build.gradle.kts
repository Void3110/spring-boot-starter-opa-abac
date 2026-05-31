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

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation("com.h2database:h2")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
