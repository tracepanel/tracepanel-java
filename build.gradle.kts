// Built with a Java 21 toolchain for Java 17: the baseline of Spring Boot 3.5 and 4.
plugins {
    `java-library`
    // The Kotlin modules' and tests' compiler, declared once for the build.
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    // Maven Central: the Central Portal's upload, the POM it requires and the signatures.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    // A Sigstore signature of each file to Maven Central, next to its GPG one: the release workflow's identity, no key.
    id("dev.sigstore.sign") version "2.3.0" apply false
}

allprojects {
    group = "dev.tracepanel"
    version = "0.9.0"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.vanniktech.maven.publish")

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
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

    // The tests on another Java than the toolchain's when -PtestJava names it: CI runs them on 17, the baseline, and
    // on the latest LTS too.
    providers.gradleProperty("testJava").orNull?.let { version ->
        val launcher = the<JavaToolchainService>().launcherFor { languageVersion = JavaLanguageVersion.of(version) }
        tasks.withType<Test>().configureEach { javaLauncher = launcher }
    }

    // A doc comment here explains the code to its reader, not an API reference: javadoc doesn't lint it.
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    }

    // On Maven Central, signed. The release workflow passes the Central Portal's token and the key as
    // ORG_GRADLE_PROJECT_mavenCentralUsername, _mavenCentralPassword, _signingInMemoryKey and
    // _signingInMemoryKeyPassword. Without the key (a local publishToMavenLocal), nothing is signed.
    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        if (providers.gradleProperty("signingInMemoryKey").isPresent) {
            signAllPublications()
        }
        pom {
            name = project.name
            description = provider { project.description }
            inceptionYear = "2026"
            url = "https://tracepanel.dev"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/license/mit"
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id = "tracepanel"
                    name = "Tracepanel"
                    url = "https://tracepanel.dev"
                }
            }
            scm {
                url = "https://github.com/tracepanel/tracepanel-java"
                connection = "scm:git:https://github.com/tracepanel/tracepanel-java.git"
                developerConnection = "scm:git:ssh://git@github.com/tracepanel/tracepanel-java.git"
            }
        }
    }

    // With -PsigstoreSign, each published file also gets a .sigstore.json: a Sigstore bundle, signed keyless with the
    // OIDC token of the GitHub Actions job (ORG_GRADLE_PROJECT_sigstoreSign in the release workflow). Elsewhere it
    // would ask for a browser sign-in, so a build without it signs nothing in Sigstore.
    if (providers.gradleProperty("sigstoreSign").isPresent) {
        apply(plugin = "dev.sigstore.sign")
    }
}
