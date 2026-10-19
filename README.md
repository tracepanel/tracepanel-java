# Tracepanel for Java

[![CI](https://github.com/tracepanel/tracepanel-java/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/tracepanel/tracepanel-java/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.tracepanel/tracepanel-core)](https://central.sonatype.com/namespace/dev.tracepanel)
[![Java](https://img.shields.io/badge/java-17%2B-blue)](https://github.com/tracepanel/tracepanel-java#tracepanel-for-java)

Tracepanel shows what each request of your app did: its SQL with the line of
your code that ran each statement, its logs and its errors. It finds what to
fix: N+1 queries, duplicates, slow SQL, long transactions. A badge on your
pages opens the panel, and the console lists every request.

This SDK records the requests of a Spring Boot or a Ktor app, in Java or
Kotlin, and sends them to Tracepanel's agent, which keeps them, finds the
issues and serves the console. It needs Java 17 or later.

| Framework | Library | Versions |
| --- | --- | --- |
| [Spring Boot](#spring-boot) | `dev.tracepanel:tracepanel-spring-boot` | Spring Boot 3.5 and 4, Spring MVC |
| [Ktor](#ktor) | `dev.tracepanel:tracepanel-ktor` | Ktor 3 |

## Spring Boot

### Install

Add the dependency for development only, so the production build leaves it
out. With Gradle:

```kotlin
// build.gradle.kts
dependencies {
    developmentOnly("dev.tracepanel:tracepanel-spring-boot:0.3.1")
}
```

With Maven, add it in a profile that only development runs use:

```xml
<profiles>
  <profile>
    <id>dev</id>
    <dependencies>
      <dependency>
        <groupId>dev.tracepanel</groupId>
        <artifactId>tracepanel-spring-boot</artifactId>
        <version>0.3.1</version>
      </dependency>
    </dependencies>
  </profile>
</profiles>
```

Run the app with Spring's `dev` profile, or with DevTools among its
dependencies: `./gradlew bootRun --args='--spring.profiles.active=dev'`, or
`./mvnw spring-boot:run -Pdev -Dspring-boot.run.profiles=dev` (`-Pdev` is
the Maven profile above, the property Spring's). Spring Boot picks
Tracepanel up by its auto-configuration: there is no code to add. Open a
page or the Swagger UI for the badge; the console is at `/__tracepanel__/`
on your app.

Tracepanel turns on in development: with a `dev`, `development` or `local`
profile, or with Spring Boot DevTools on the classpath. Elsewhere it stays
off, even if the dependency reached the build by mistake, and says so once in
the log. `TRACEPANEL_ENABLED=1` turns it on anyway, `TRACEPANEL_ENABLED=0`
off.

![The Tracepanel panel docked under a Spring Boot app's Swagger UI, showing the call tree of GET /api/catalog/products with two N+1 queries](docs/images/spring-boot.png)

### What it records

- Each request, named by its route (`GET /owners/{id}`), with the controller
  method that served it.
- SQL through JDBC, whatever runs it (Spring Data JPA, Hibernate,
  `JdbcTemplate`, jOOQ): each statement with its parameters, the rows it
  returned and the line of your code that ran it, and each transaction.
- Logback records made during a request, with the line that logged them.
- Errors a handler throws, with your frames and the source around them. An
  exception the app answers with a 4xx is the answer, not an error.
- Calls to HTTP APIs through `RestClient`, `RestTemplate`, `WebClient` and
  the JDK's `HttpClient` (a bean, or one you wrap with
  `HttpCalls.wrap(client)`), each with a `traceparent`, so the app it
  reaches links its trace to the call.
- Redis commands through Lettuce (Spring Data Redis, the cache) and MongoDB
  commands (Spring Data MongoDB), with their N+1 and duplicates.
- Kafka and RabbitMQ messages a `KafkaTemplate` or a `RabbitTemplate`
  sends, with their N+1. Each message a `@KafkaListener` or
  `@RabbitListener` receives is a trace of its own, delivered from the one
  that sent it.
- A handler that answers later (a Kotlin suspend function, a `Callable`, a
  `DeferredResult`) is one trace until its answer. A suspend function's
  queries are in it on whichever thread they run: `Dispatchers.IO`, after
  a `delay`, in `async` blocks.
- Work besides requests: an `@Async` method's run is a task, linked to the
  request that queued it; an `@Scheduled` method's run and a
  `CommandLineRunner`'s are jobs.
- The signed-in user: the principal's name from Spring Security, when the
  app has it, or the user your code names with
  `Tracepanel.setUser(id, username)`. The username stays on your machine.

WebFlux apps aren't supported yet.

## Ktor

### Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tracepanel:tracepanel-ktor:0.3.1")
}
```

Install the plugin. Ktor has no dependency injection to find your DataSource
or your HTTP client by, so you hand them over:

```kotlin
fun Application.module() {
    install(Tracepanel)
    val db = Database.connect(Jdbc.dataSource(hikari, "default"))
    val http = HttpClient(CIO) { install(TracepanelClient) }
}
```

Tracepanel is on in Ktor's development mode (`-Dio.ktor.development=true`,
or `ktor.development` in the config), or with `TRACEPANEL_ENABLED=1`. The
dependency goes into the production build, where it stays off. The console
is at `/__tracepanel__/` on your app.

![The Tracepanel panel docked under a Ktor app's Swagger UI, showing the call tree of GET /api/catalog/products with three N+1 queries](docs/images/ktor.png)

### What it records

- Each call, named by its route (`GET /books/{id}`), with its handler as a
  span of the same name. Its coroutines carry the trace to every thread
  they run on, so the queries of a suspended transaction on
  `Dispatchers.IO` are in it.
- SQL through the DataSource `Jdbc.dataSource` wraps, whatever runs it
  (Exposed, plain JDBC): each statement with its parameters, the rows it
  returned and the line of your code that ran it, and each transaction.
- Calls through the client with `TracepanelClient`, each with a
  `traceparent`.
- Logback records made during a call, with the line that logged them.
- Errors a call fails with. One that StatusPages answers with a 4xx is the
  answer, not an error.
- The badge on HTML pages, however they were written (`respondText`,
  `respondHtml`).
- The signed-in user your code names with
  `call.tracepanelUser(id, username)`.

## In every app

Every response carries `Tracepanel-Id` and `Server-Timing`. A single-page app
on another origin reads them too: the SDK exposes them on CORS responses. A
request that carries a W3C `traceparent` continues the caller's trace.

The agent is a program of its own, which keeps the traces and finds the
issues. You don't install it: the SDK downloads its own version of it the
first time, from the agent's npm package for your platform, checks it
against the checksum it was built with, and keeps it in
`~/.cache/tracepanel/` for every project. `TRACEPANEL_AGENT_BIN` names
another one, and without a network the SDK runs `tracepanel-agent` from the
`PATH`.

Behind a company proxy, the download goes through `HTTPS_PROXY`, as curl
and npm read it, with `NO_PROXY` for the hosts that skip it. Without them it
uses the JVM's proxy (`-Dhttps.proxyHost`, or
`-Djava.net.useSystemProxies=true` for the system's). A login goes in the
proxy's URL: `http://user:password@proxy.corp:3128`. For a proxy that asks
for a Basic login, start the JVM with
`-Djdk.http.auth.tunneling.disabledSchemes=`, as the JDK sends none to an
https site through a proxy otherwise. With an npm mirror, such as
Artifactory or Nexus, `TRACEPANEL_NPM_REGISTRY` names it. The checksum is
the same wherever the agent comes from.

The console answers this machine only: the traces hold SQL parameters, logs
and errors, and EXPLAIN runs queries. When the app runs in a container, or on
a shared dev server, `TRACEPANEL_CONSOLE_REMOTE=1` lets other machines open
it. Values under a secret's name (`password`, `token`, `api_key`, `session`,
…) are masked before they are recorded: in the query of an outgoing call's
URL, which also loses its `user:password@`, and in a SQL parameter bound to
such a column (`password = ?`, `INSERT INTO users (email, password)`).

Add `.tracepanel/` to your `.gitignore`: it holds the project's traces.

## Settings

The same environment variables as the other Tracepanel SDKs:

| Variable | Default | What it sets |
| --- | --- | --- |
| `TRACEPANEL_ENABLED` | in development (a `dev` or `local` profile or DevTools; Ktor's development mode) | `0` turns Tracepanel off, `1` on |
| `TRACEPANEL_SERVICE` | `spring.application.name`, or Ktor's `ktor.application.id` | the app's name in the console |
| `TRACEPANEL_DIR` | `.tracepanel` | the project's store, where the agent keeps the traces |
| `TRACEPANEL_AGENT_BIN` | the one the SDK downloads | the agent to start, such as a build of your own |
| `TRACEPANEL_AGENT` | `auto` | an agent elsewhere (`tcp:host:port`), never started |
| `TRACEPANEL_CACHE_DIR` | `~/.cache/tracepanel` | where the downloaded agent is kept |
| `TRACEPANEL_NPM_REGISTRY` | npm's `registry` setting, else `https://registry.npmjs.org/` | the npm registry the agent is downloaded from |
| `HTTPS_PROXY`, `NO_PROXY` | the JVM's proxy | the proxy for the agent's download, and the hosts it skips |
| `TRACEPANEL_CONSOLE_REMOTE` | off | `1` lets other machines open the console |
| `TRACEPANEL_N_PLUS_ONE_THRESHOLD` | `3` | repeats that make an N+1 |
| `TRACEPANEL_SLOW_SQL_MS` | `100` | a slow query |
| `TRACEPANEL_LONG_TRANSACTION_MS` | `500` | a long transaction |
| `TRACEPANEL_LARGE_RESULT_ROWS` | `1000` | a query without a LIMIT that read too many rows |

## Build

```bash
./gradlew check
```

It runs the tests on Spring Boot 4 and on 3.5, and on Ktor. The tests of
MongoDB, Redis, Kafka and RabbitMQ need a server of each:
`TEST_MONGODB_URI=mongodb://127.0.0.1:27017/test`, `TEST_REDIS_PORT=6379`,
`TEST_KAFKA_BOOTSTRAP=localhost:9092` and `TEST_RABBITMQ_PORT=5672`. After
changing `agentVersion` in `gradle.properties`, `./gradlew agentIntegrity`
writes the checksums of that version's downloads.

`tracepanel-core` records traces and talks to the agent, with no framework
and no dependencies. `tracepanel-spring-boot` is the Spring Boot
integration, `tracepanel-ktor` the Ktor one.

## License

MIT
