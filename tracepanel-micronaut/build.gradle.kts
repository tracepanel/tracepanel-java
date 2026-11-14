description = "Tracepanel for Micronaut: request traces, JDBC, declarative HTTP clients, logs, a badge and the local console."

val micronaut4 = "4.10.24"
val micronaut5 = "5.2.6"
val h2 = "2.5.252"
val logback = "1.6.6"

dependencies {
    api(project(":tracepanel-core"))
    compileOnly(platform("io.micronaut.platform:micronaut-platform:$micronaut4"))
    compileOnly("io.micronaut:micronaut-http-server")
    compileOnly("io.micronaut:micronaut-http-client-core")
    compileOnly("io.micronaut:micronaut-core-reactive")
    compileOnly("org.jetbrains:annotations:26.1.0")
    implementation("io.micronaut.reactor:micronaut-reactor:3.9.1")
    implementation("io.projectreactor:reactor-core:3.7.19")
    compileOnly("ch.qos.logback:logback-classic:$logback")
    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronaut4"))
    annotationProcessor("io.micronaut:micronaut-inject-java")

    testImplementation(platform("io.micronaut.platform:micronaut-platform:$micronaut4"))
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.micronaut:micronaut-http-server-netty")
    testImplementation("io.micronaut:micronaut-http-client")
    testImplementation("io.micronaut:micronaut-jackson-databind")
    testImplementation("io.micronaut.reactor:micronaut-reactor")
    testImplementation("io.micronaut.sql:micronaut-jdbc-hikari")
    testImplementation("io.projectreactor:reactor-core")
    testImplementation("com.h2database:h2:$h2")
    testImplementation("ch.qos.logback:logback-classic:$logback")
    testImplementation(testFixtures(project(":tracepanel-core")))
    testAnnotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronaut4"))
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
}

// Compile against Micronaut 4's Java 17 API; exercise the same integration on Micronaut 5 and Java 25.
val micronaut5Tests = testing.suites.register<JvmTestSuite>("testMicronaut5") {
    useJUnitJupiter("6.1.3")
    sources.java.setSrcDirs(listOf("src/test/java"))
    dependencies {
        implementation(project())
        implementation(platform("io.micronaut.platform:micronaut-platform:$micronaut5"))
        implementation("io.micronaut.test:micronaut-test-junit5")
        implementation("io.micronaut:micronaut-http-server-netty")
        implementation("io.micronaut:micronaut-http-client")
        implementation("io.micronaut:micronaut-jackson-databind")
        implementation("io.micronaut.reactor:micronaut-reactor")
        implementation("io.micronaut.sql:micronaut-jdbc-hikari")
        implementation("io.projectreactor:reactor-core:3.8.8")
        implementation("com.h2database:h2:$h2")
        implementation("ch.qos.logback:logback-classic:$logback")
        implementation(testFixtures(project(":tracepanel-core")))
    }
    targets.all {
        testTask.configure {
            javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
            useJUnitPlatform { excludeTags("integration") }
        }
    }
}
dependencies {
    "testMicronaut5AnnotationProcessor"(platform("io.micronaut.platform:micronaut-platform:$micronaut5"))
    "testMicronaut5AnnotationProcessor"("io.micronaut:micronaut-inject-java")
}
tasks.named<JavaCompile>("compileTestMicronaut5Java") {
    javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
    options.release = 25
}
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-processing")
    options.compilerArgs.addAll(listOf("-Amicronaut.processing.group=dev.tracepanel", "-Amicronaut.processing.module=tracepanel-micronaut"))
}
tasks.withType<Test>().configureEach {
    environment("TRACEPANEL_DIR", layout.buildDirectory.dir("tracepanel-test/$name").get().asFile.absolutePath)
    environment("TRACEPANEL_AGENT", "off")
}

// Separate JVMs keep the core's process-wide recorder and fake agent out of unit tests.
tasks.test { useJUnitPlatform { excludeTags("integration") } }
val integrationTest = tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    environment("TRACEPANEL_AGENT", "auto")
    shouldRunAfter(tasks.test)
}
tasks.register<Test>("testMicronaut5Integration") {
    val sources = micronaut5Tests.get().sources
    testClassesDirs = sources.output.classesDirs
    classpath = sources.runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    useJUnitPlatform { includeTags("integration") }
    environment("TRACEPANEL_AGENT", "auto")
    shouldRunAfter(tasks.named("testMicronaut5"))
}
tasks.check { dependsOn(integrationTest) }

// The auto-configured integration has package-private beans rather than a public API.
tasks.javadoc {
    (options as StandardJavadocDocletOptions).memberLevel = JavadocMemberLevel.PACKAGE
}
