// Tracepanel for the JVM: tracepanel-core records traces and sends them to the project's agent, with no framework;
// tracepanel-spring-boot traces a Spring Boot app with one dependency.
rootProject.name = "tracepanel-java"

include("tracepanel-core", "tracepanel-spring-boot")
