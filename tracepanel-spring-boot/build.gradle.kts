// Spring Boot's own libraries are the app's: compiled against, never brought along.
dependencies {
    api(project(":tracepanel-core"))

    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("ch.qos.logback:logback-classic")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
}

tasks.test {
    // The tests' fake agent leaves its addresses here, where the SDK looks for the project's.
    environment("TRACEPANEL_DIR", layout.buildDirectory.dir("tracepanel-test").get().asFile.absolutePath)
    environment("TRACEPANEL_AGENT", "auto")
}
