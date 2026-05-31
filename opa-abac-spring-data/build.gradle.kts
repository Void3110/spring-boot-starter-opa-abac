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

    // `api`, not `implementation`: the base entities and CRUD service expose JPA/Spring Data types
    // (jakarta.persistence, JpaRepository, AbacDataObject), so consumers inherit JPA on their compile
    // classpath. Jackson (used by ResourceTagsConverter) comes transitively via this starter.
    api("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
}
