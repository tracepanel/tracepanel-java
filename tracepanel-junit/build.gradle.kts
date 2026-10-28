import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // The tests' Ktor app: Kotlin compiles them, as a Ktor app's build does.
    kotlin("jvm")
}

description = "Tracepanel for JUnit: each test a trace, failed when it or the requests it makes have an N+1 or duplicate queries."

// JUnit is the project's own: compiled against the oldest Jupiter supported (Spring Boot 3.5's), never brought along.
dependencies {
    api(project(":tracepanel-core"))
    compileOnly("org.junit.jupiter:junit-jupiter-api:5.12.2")

    testImplementation(testFixtures(project(":tracepanel-core")))
    testImplementation("org.junit.platform:junit-platform-testkit")
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-webtestclient")
    testImplementation("org.springframework:spring-webflux")
    testImplementation("io.projectreactor.netty:reactor-netty-http")
    testImplementation("com.h2database:h2")
    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
    testImplementation("io.ktor:ktor-server-core:3.6.0")
    testImplementation(project(":tracepanel-ktor"))
    testImplementation(project(":tracepanel-spring-boot"))
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<Test>().configureEach {
    // The tests' fake agent leaves its addresses here, where the SDK looks for the project's.
    environment("TRACEPANEL_DIR", layout.buildDirectory.dir("tracepanel-test/$name").get().asFile.absolutePath)
    environment("TRACEPANEL_AGENT", "auto")
    // The example tests run by the JUnit Platform's test kit, in the tests: not on their own.
    useJUnitPlatform { excludeTags("example") }
}

// Tests that need a JVM of their own, as Tracepanel is one per process: without an agent (TRACEPANEL_AGENT=off), and
// with the real agent and a real database (H2), where the agent finds the N+1 a test makes and the test fails with it.
// The SDK downloads the agent of its version for that, as in a project, unless TEST_AGENT_BIN names one.
val agentBin: String? = System.getenv("TEST_AGENT_BIN")
val own = mapOf("testNoAgent" to "shop.NoAgentTest", "testLive" to "shop.LiveTest")
for ((task, test) in own) {
    val registered = tasks.register<Test>(task) {
        description = "Runs $test in a JVM of its own."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter { includeTestsMatching(test) }
        shouldRunAfter(tasks.test)
        if (task == "testNoAgent") environment("TRACEPANEL_AGENT", "off")
        if (task == "testLive") agentBin?.let { environment("TRACEPANEL_AGENT_BIN", it) }
        // The agent outlives the tests: stopped once they ran.
        val store = layout.buildDirectory.dir("tracepanel-test/$task").get().asFile.absolutePath
        doLast {
            ProcessHandle.allProcesses()
                .filter { it.info().commandLine().orElse("").contains("--dir $store") }
                .forEach { it.destroy() }
        }
    }
    tasks.check { dependsOn(registered) }
}
tasks.test {
    filter { own.values.forEach { excludeTestsMatching(it) } }
}
