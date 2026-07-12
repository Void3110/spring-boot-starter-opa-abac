import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

description = "Bill of materials pinning all OPA ABAC library modules at one version"

// A pure constraints platform: it manages versions, it does not itself depend on anything.
// `allowDependencies()` is intentionally NOT called — the BOM contributes only
// <dependencyManagement>, never transitive <dependencies>.
dependencies {
    constraints {
        api(project(":opa-abac-core"))
        api(project(":opa-abac-spring-security"))
        api(project(":opa-abac-spring-data"))
        api(project(":opa-abac-keycloak-directory"))
        api(project(":opa-abac-spring-boot-starter"))
    }
}

// Publish wiring for the 6th coordinate. A java-platform publishes a POM-only artifact — no jar,
// no sources/javadoc — so configure(JavaPlatform()) rather than JavaLibrary(...). The plugin is
// applied here (in the module) rather than via the root allow-list, which is jar-only.
mavenPublishing {
    publishToMavenCentral()   // Central Portal is the default host (no SonatypeHost arg)
    signAllPublications()     // GPG signing; keys come from ~/.gradle only (never the repo)
    configure(JavaPlatform())
}
