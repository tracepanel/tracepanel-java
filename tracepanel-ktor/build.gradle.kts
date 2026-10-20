import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A Ktor app's requests, its client's calls and its coroutines, as Spring's are: one plugin. Ktor and Kotlin are the
// app's own: compiled against, never brought along.
plugins {
    kotlin("jvm")
}

description = "Tracepanel for Ktor: each call's SQL, logs, errors and outgoing calls, its N+1 queries, a badge on its pages and a console at /__tracepanel__/."

val ktor = "3.6.0"

dependencies {
    api(project(":tracepanel-core"))

    compileOnly("io.ktor:ktor-server-core:$ktor")
    compileOnly("io.ktor:ktor-client-core:$ktor")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("ch.qos.logback:logback-classic:1.5.18")
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")

    testImplementation(testFixtures(project(":tracepanel-core")))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    // A real server for a page that streams: the test host hands the client a response once it is complete.
    testImplementation("io.ktor:ktor-server-cio:$ktor")
    testImplementation("io.ktor:ktor-server-core:$ktor")
    testImplementation("io.ktor:ktor-client-core:$ktor")
    testImplementation("io.ktor:ktor-client-cio:$ktor")
    testImplementation("io.ktor:ktor-server-status-pages:$ktor")
    testImplementation("io.ktor:ktor-server-html-builder:$ktor")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("com.h2database:h2:2.3.232")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<Test>().configureEach {
    environment("TRACEPANEL_DIR", layout.buildDirectory.dir("tracepanel-test").get().asFile.absolutePath)
    environment("TRACEPANEL_AGENT", "auto")
    environment("TRACEPANEL_ENABLED", "1")
    useJUnitPlatform()
}
