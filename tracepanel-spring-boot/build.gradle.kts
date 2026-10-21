import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // The tests' Kotlin app (coroutines): Kotlin compiles them, as a Kotlin app's build does, open for Spring's proxies.
    kotlin("jvm")
    kotlin("plugin.spring")
}

description = "Tracepanel for Spring Boot: each request's SQL, logs, errors and calls, its N+1 queries, a badge on its pages and a console at /__tracepanel__/."

// Spring Boot's own libraries are the app's: compiled against, never brought along.
dependencies {
    api(project(":tracepanel-core"))

    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("ch.qos.logback:logback-classic")
    compileOnly("org.apache.logging.log4j:log4j-core")
    // The annotations log4j's classes carry, which it leaves out: without them -Xlint:classfile fails the build.
    compileOnly("biz.aQute.bnd:biz.aQute.bnd.annotation:7.4.0")
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.10.5")
    compileOnly("org.springframework:spring-webflux")
    compileOnly("io.projectreactor:reactor-core")
    compileOnly("org.mongodb:mongodb-driver-core")
    compileOnly("io.lettuce:lettuce-core")
    compileOnly("org.springframework.data:spring-data-redis")
    compileOnly("org.springframework.kafka:spring-kafka")
    compileOnly("org.springframework.amqp:spring-rabbit")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    compileOnly("org.springframework.security:spring-security-core")

    testImplementation(testFixtures(project(":tracepanel-core")))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-starter-webclient")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-amqp")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// The same tests on Spring Boot 3.5, the line most apps are on: compiled and run against its libraries.
testing {
    suites {
        register<JvmTestSuite>("testBoot35") {
            useJUnitJupiter()
            sources {
                java.setSrcDirs(listOf("src/test/java"))
                resources.setSrcDirs(listOf("src/test/resources"))
            }
            dependencies {
                implementation(project())
                implementation(testFixtures(project(":tracepanel-core")))
                implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-jdbc")
                implementation("org.springframework.boot:spring-boot-starter-webflux")
                implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
                implementation("org.springframework.boot:spring-boot-starter-data-redis")
                implementation("org.springframework.kafka:spring-kafka")
                implementation("org.springframework.boot:spring-boot-starter-amqp")
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("com.h2database:h2")
                implementation("org.springframework.security:spring-security-core")
            }
            targets.all {
                testTask.configure { shouldRunAfter(tasks.test) }
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // The tests' fake agent leaves its addresses here, where the SDK looks for the project's.
    environment("TRACEPANEL_DIR", layout.buildDirectory.dir("tracepanel-test/$name").get().asFile.absolutePath)
    environment("TRACEPANEL_AGENT", "auto")
    // MongoDB and Redis for the tests that need them, when given (Spring Boot 3.5's and 4's property names).
    System.getenv("TEST_MONGODB_URI")?.let {
        environment("SPRING_DATA_MONGODB_URI", it)
        environment("SPRING_MONGODB_URI", it)
    }
    System.getenv("TEST_REDIS_PORT")?.let { environment("SPRING_DATA_REDIS_PORT", it) }
    // Kafka and RabbitMQ, when given; without them the listener containers don't start.
    val kafka = System.getenv("TEST_KAFKA_BOOTSTRAP")
    val rabbit = System.getenv("TEST_RABBITMQ_PORT")
    if (kafka != null) environment("SPRING_KAFKA_BOOTSTRAP_SERVERS", kafka)
    environment("SPRING_KAFKA_LISTENER_AUTO_STARTUP", (kafka != null).toString())
    environment("SPRING_KAFKA_CONSUMER_AUTO_OFFSET_RESET", "earliest")
    if (rabbit != null) environment("SPRING_RABBITMQ_PORT", rabbit)
    environment("SPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP", (rabbit != null).toString())
}

// The console's Live with the real agent, when TEST_AGENT_BIN names its binary (a build of it from source, before a
// release that has what the test asks for): the app starts it, as in development, and the test stops it. The other
// test tasks talk to the tests' fake agent and leave this one out.
val agentTest = "shop.LiveTasksAgentTest"
val agentBin: String? = System.getenv("TEST_AGENT_BIN")
val testAgent by tasks.registering(Test::class) {
    description = "Runs the tests against the real agent named by TEST_AGENT_BIN."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching(agentTest) }
    onlyIf { agentBin != null }
    agentBin?.let { environment("TRACEPANEL_AGENT_BIN", it) }
    shouldRunAfter(tasks.test)
    // The agent outlives the app it started: stopped here too, should the tests not get to it.
    val store = layout.buildDirectory.dir("tracepanel-test/testAgent").get().asFile.absolutePath
    doLast {
        ProcessHandle.allProcesses()
            .filter { it.info().commandLine().orElse("").contains("--dir $store") }
            .forEach { it.destroy() }
    }
}
tasks.withType<Test>().matching { it.name != testAgent.name }.configureEach {
    filter { excludeTestsMatching(agentTest) }
}

tasks.check {
    dependsOn(testing.suites.named("testBoot35"))
    dependsOn(testAgent)
}
