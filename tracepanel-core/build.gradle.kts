import java.net.URI
import java.security.MessageDigest
import java.util.Base64

// No dependencies: it runs inside the app, whatever the app depends on. The integrations with a driver or a logging
// library (dev.tracepanel.integrations) compile against the oldest versions supported, Spring Boot 3.5's, and run with
// the app's own.

plugins {
    // The tests' fake agent, for every module's tests.
    `java-test-fixtures`
}

description = "Tracepanel's recorder for the JVM: traces a request's SQL, logs, errors and calls, and sends them to Tracepanel's agent."

dependencies {
    testFixturesImplementation("com.jayway.jsonpath:json-path:2.9.0")
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    compileOnly("ch.qos.logback:logback-classic")
    compileOnly("org.mongodb:mongodb-driver-core")
    compileOnly("io.lettuce:lettuce-core")
    compileOnly("org.apache.kafka:kafka-clients")
}


// The agent's platforms, as its npm packages and its classifiers on Maven Central name them.
val platforms = listOf(
    "darwin-arm64", "darwin-x64",
    "linux-x64-gnu", "linux-arm64-gnu", "linux-ia32-gnu", "linux-ppc64-gnu", "linux-s390x-gnu", "linux-riscv64-gnu",
    "linux-x64-musl", "linux-arm64-musl", "linux-ia32-musl", "linux-arm",
    "win32-x64", "win32-ia32", "win32-arm64",
)

// Run when agentVersion changes: the SHA-512 of each platform's binary, kept in the source. It is the binary's, not a
// package's, so it holds wherever the SDK downloads it from: Maven Central, a mirror, or npm. Read here from npm, whose
// packages hold the same binaries.
tasks.register("agentIntegrity") {
    description = "Writes the agent version's binary checksums into agent.properties."
    val version = project.property("agentVersion") as String
    val out = file("src/main/resources/dev/tracepanel/core/agent.properties")
    val scratch = layout.buildDirectory.dir("agent-integrity")
    doLast {
        val lines = mutableListOf(
            "# The agent the SDK downloads when none is installed: the SHA-512 of each platform's binary.",
            "# Written by ./gradlew agentIntegrity; agentVersion in gradle.properties names it.",
            "version=$version",
        )
        for (platform in platforms) {
            val tarball = scratch.get().file("$platform.tgz").asFile
            tarball.parentFile.mkdirs()
            URI("https://registry.npmjs.org/@tracepanel/agent-$platform/-/agent-$platform-$version.tgz").toURL()
                .openStream().use { input -> tarball.outputStream().use { input.copyTo(it) } }
            val binary = project.tarTree(project.resources.gzip(tarball)).matching { include("package/bin/*") }.singleFile
            val digest = MessageDigest.getInstance("SHA-512").digest(binary.readBytes())
            lines += "$platform=sha512-${Base64.getEncoder().encodeToString(digest)}"
        }
        out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

// The tests' fake agent stays out of the published library: its jar, and the sources jar the publishing plugin adds
// once the project is evaluated.
afterEvaluate {
    val java = components["java"] as AdhocComponentWithVariants
    for (name in listOf("testFixturesApiElements", "testFixturesRuntimeElements", "testFixturesSourcesElements")) {
        configurations.findByName(name)?.let { java.withVariantsFromConfiguration(it) { skip() } }
    }
}
