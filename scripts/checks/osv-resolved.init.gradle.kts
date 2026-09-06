// osv-resolved.init.gradle.kts — the FIRST half of the dependency CVE sweep.
//
// Prints every RESOLVED runtimeClasspath coordinate of every project as `RESOLVED <project> g:a:v`.
// Resolved, never declared: the dependency TREE lists versions dependency management then overrides
// (the 1.2.0 pre-publish sweep would have reported nine advisories that exist on no classpath by
// reading the tree — docs/code-review/PRE-PUBLISH-SWEEP-2026-08-18.md §CVE). Pipe into osv-sweep.py.
//
//   ./gradlew -q -I scripts/checks/osv-resolved.init.gradle.kts printResolvedRuntime \
//     | python3 scripts/checks/osv-sweep.py
//
// Manual gate, not CI: the second half needs api.osv.dev. Run it on every Boot / dependency bump
// (ENGINEERING-BACKLOG item 9/10 — the BOM-managed advisories are closed by bumps, not by overrides).
allprojects {
    tasks.register("printResolvedRuntime") {
        doLast {
            val cfg = configurations.findByName("runtimeClasspath") ?: return@doLast
            cfg.resolvedConfiguration.resolvedArtifacts
                .map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}:${it.moduleVersion.id.version}" }
                .distinct().sorted()
                .forEach { println("RESOLVED ${project.path} $it") }
        }
    }
}
