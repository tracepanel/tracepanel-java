// Tracepanel for the JVM: tracepanel-core records traces and sends them to the project's agent, with no framework;
// tracepanel-spring-boot traces a Spring Boot app with one dependency, tracepanel-ktor a Ktor app with one plugin.
rootProject.name = "tracepanel-java"

include("tracepanel-core", "tracepanel-spring-boot", "tracepanel-ktor")
