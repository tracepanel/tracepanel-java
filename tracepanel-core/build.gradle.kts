// No dependencies: it runs inside the app, whatever the app depends on.

import java.net.URI
import groovy.json.JsonSlurper

// The agent's npm platform packages: the binary for each platform, and npm's integrity (SHA-512) of its tarball.
val platforms = listOf(
    "darwin-arm64", "darwin-x64",
    "linux-x64-gnu", "linux-arm64-gnu", "linux-ia32-gnu", "linux-ppc64-gnu", "linux-s390x-gnu", "linux-riscv64-gnu",
    "linux-x64-musl", "linux-arm64-musl", "linux-ia32-musl", "linux-arm",
    "win32-x64", "win32-ia32", "win32-arm64",
)

// Run when agentVersion changes: the downloads' checksums, from npm's registry, kept in the source.
tasks.register("agentIntegrity") {
    description = "Writes the agent version's download checksums into agent.properties."
    val version = project.property("agentVersion") as String
    val out = file("src/main/resources/dev/tracepanel/core/agent.properties")
    doLast {
        val lines = mutableListOf(
            "# The agent the SDK downloads when none is installed: npm's integrity of each platform's package.",
            "# Written by ./gradlew agentIntegrity from the registry; agentVersion in gradle.properties names it.",
            "version=$version",
        )
        for (platform in platforms) {
            val meta = URI("https://registry.npmjs.org/@tracepanel%2Fagent-$platform/$version").toURL().readText()
            @Suppress("UNCHECKED_CAST")
            val dist = (JsonSlurper().parseText(meta) as Map<String, Any>)["dist"] as Map<String, Any>
            lines += "$platform=${dist["integrity"]}"
        }
        out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
