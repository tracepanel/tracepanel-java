# Tracepanel for Java, Spring Boot and Ktor

Tracepanel shows what each request of your app did: its SQL with the line of
your code that ran each statement, its logs and its errors. It finds what to
fix: N+1 queries, duplicates, slow SQL, long transactions. A badge on your
pages opens the panel; the console lists every request.

This SDK records a Spring Boot app's requests and sends them to Tracepanel's
agent, which keeps them, finds the issues and serves the console.

## Setup

Add the dependency for development only, so the production build leaves it
out:

```kotlin
// build.gradle.kts
dependencies {
    developmentOnly("dev.tracepanel:tracepanel-spring-boot:0.3.0")
}
```

With Maven, add it as an optional dependency in a profile your production
build doesn't use.

Run the app as you do (`./gradlew bootRun`). The first request starts the
agent, and its console answers on your app at `/__tracepanel__/`.

The agent is a program of its own, which keeps the traces and finds the
issues. You don't install it: the SDK downloads its own version of it the
first time, from the agent's npm package for your platform, checks it
against the checksum it was built with, and keeps it in
`~/.cache/tracepanel/` for every project. `TRACEPANEL_AGENT_BIN` names
another one, and without a network the SDK runs `tracepanel-agent` from the
`PATH`.

Spring Boot 3.5 and 4 with Spring MVC, on Java 17 or later, in Java or in
Kotlin. WebFlux comes next.

![The Tracepanel panel docked under a Spring Boot app's Swagger UI, showing the call tree of GET /api/catalog/products with two N+1 queries](docs/images/spring-boot.png)

## Ktor

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tracepanel:tracepanel-ktor:0.3.0")
}
```

```kotlin
fun Application.module() {
    install(Tracepanel)
    val db = Database.connect(Jdbc.dataSource(hikari, "default"))
    val http = HttpClient(CIO) { install(TracepanelClient) }
}
```

Each call is a trace named by its route (`GET /books/{id}`). Its coroutines
carry it to every thread they run on, so the queries of a suspended
transaction on `Dispatchers.IO` are in it. `Jdbc.dataSource` records a
DataSource's SQL (Ktor has no DI to find it by), and `TracepanelClient`
the client's calls. An HTML page gets the badge, however it was written,
and the console answers at `/__tracepanel__/`. Name the signed-in user with
`call.tracepanelUser(id, username)`.

Tracepanel is on in Ktor's development mode (`-Dio.ktor.development=true`,
or `ktor.development` in the config), or with `TRACEPANEL_ENABLED=1`. The
dependency goes into the production build, where it stays off.

![The Tracepanel panel docked under a Ktor app's Swagger UI, showing the call tree of GET /api/catalog/products with three N+1 queries](docs/images/ktor.png)

## What it records

- Each request, named by its route (`GET /owners/{id}`), with the controller
  method that served it.
- SQL through JDBC, whatever runs it (Spring Data JPA, Hibernate,
  `JdbcTemplate`, jOOQ): each statement with its parameters, the rows it
  returned and the line of your code that ran it, and each transaction.
- Logback records made during a request, with the line that logged them.
- Errors a handler throws, with your frames and the source around them.
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
- The caller's trace, when a request carries a W3C `traceparent`.

Every response carries `Tracepanel-Id` and `Server-Timing`; an HTML page gets
the badge. A single-page app on another origin reads them too: the SDK
exposes them on CORS responses.

## Settings

The same environment variables as the other Tracepanel SDKs:

| Variable | Default | What it sets |
| --- | --- | --- |
| `TRACEPANEL_ENABLED` | on | `0` turns Tracepanel off |
| `TRACEPANEL_SERVICE` | `spring.application.name` | the app's name in the console |
| `TRACEPANEL_DIR` | `.tracepanel` | the project's store, where the agent keeps the traces |
| `TRACEPANEL_AGENT_BIN` | the one the SDK downloads | the agent to start, such as a build of your own |
| `TRACEPANEL_AGENT` | `auto` | an agent elsewhere (`tcp:host:port`), never started |
| `TRACEPANEL_CACHE_DIR` | `~/.cache/tracepanel` | where the downloaded agent is kept |
| `TRACEPANEL_N_PLUS_ONE_THRESHOLD` | `3` | repeats that make an N+1 |
| `TRACEPANEL_SLOW_SQL_MS` | `100` | a slow query |
| `TRACEPANEL_LONG_TRANSACTION_MS` | `500` | a long transaction |
| `TRACEPANEL_LARGE_RESULT_ROWS` | `1000` | a query without a LIMIT that read too many rows |

## Build

```bash
./gradlew check
```

It runs the tests on Spring Boot 4 and on 3.5. The tests of MongoDB,
Redis, Kafka and RabbitMQ need a server of each:
`TEST_MONGODB_URI=mongodb://127.0.0.1:27017/test`, `TEST_REDIS_PORT=6379`,
`TEST_KAFKA_BOOTSTRAP=localhost:9092` and `TEST_RABBITMQ_PORT=5672`. After changing `agentVersion` in
`gradle.properties`, `./gradlew agentIntegrity` writes the checksums of
that version's downloads.

`tracepanel-core` records traces and talks to the agent, with no framework and
no dependencies. `tracepanel-spring-boot` is the Spring Boot integration,
`tracepanel-ktor` the Ktor one.

## License

MIT
