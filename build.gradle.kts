// Built with a Java 21 toolchain for Java 17: the baseline of Spring Boot 3.5 and 4.
plugins {
    `java-library`
}

allprojects {
    group = "dev.tracepanel"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 17
        options.encoding = "UTF-8"
        // -parameters: Spring binds @PathVariable and friends by the parameter's name, as its own Gradle plugin compiles.
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Werror", "-parameters"))
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
