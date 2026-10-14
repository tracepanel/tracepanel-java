// Spring Boot's own libraries are the app's: compiled against, never brought along.
dependencies {
    api(project(":tracepanel-core"))

    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("ch.qos.logback:logback-classic")
    compileOnly("org.springframework:spring-webflux")
    compileOnly("io.projectreactor:reactor-core")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-starter-webclient")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
}

// The same tests on Spring Boot 3.5, the line most apps are on: compiled and run against its libraries.
testing {
    suites {
        register<JvmTestSuite>("testBoot35") {
            useJUnitJupiter()
            sources {
                java.setSrcDirs(listOf("src/test/java"))
            }
            dependencies {
                implementation(project())
                implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-jdbc")
                implementation("org.springframework.boot:spring-boot-starter-webflux")
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("com.h2database:h2")
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
}

tasks.check {
    dependsOn(testing.suites.named("testBoot35"))
}
