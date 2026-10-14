# Tracepanel for Java and Spring Boot

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
    developmentOnly("dev.tracepanel:tracepanel-spring-boot:0.1.0")
}
```

With Maven, add it as an optional dependency in a profile your production
build doesn't use.

Run the app as you do (`./gradlew bootRun`). The first request starts the
agent, with `tracepanel-agent` on the `PATH` or named by
`TRACEPANEL_AGENT_BIN`. Its console answers on your app at `/__tracepanel__/`.

Spring Boot 4 with Spring MVC, on Java 17 or later. WebFlux, Kotlin
coroutines and Spring Boot 3.5 come next.

## What it records

- Each request, named by its route (`GET /owners/{id}`), with the controller
  method that served it.
- SQL through JDBC, whatever runs it (Spring Data JPA, Hibernate,
  `JdbcTemplate`, jOOQ): each statement with its parameters, the rows it
  returned and the line of your code that ran it, and each transaction.
- Logback records made during a request, with the line that logged them.
- Errors a handler throws, with your frames and the source around them.
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
| `TRACEPANEL_AGENT_BIN` | `tracepanel-agent` on the `PATH` | the agent to start |
| `TRACEPANEL_N_PLUS_ONE_THRESHOLD` | `3` | repeats that make an N+1 |
| `TRACEPANEL_SLOW_SQL_MS` | `100` | a slow query |
| `TRACEPANEL_LONG_TRANSACTION_MS` | `500` | a long transaction |
| `TRACEPANEL_LARGE_RESULT_ROWS` | `1000` | a query without a LIMIT that read too many rows |

## Build

```bash
./gradlew test
```

`tracepanel-core` records traces and talks to the agent, with no framework and
no dependencies. `tracepanel-spring-boot` is the Spring Boot integration.

## License

MIT
