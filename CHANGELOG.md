# Changelog

## 0.3.0 (2026-10-18)

The first release. The SDK downloads `tracepanel-agent` 0.3.3 the first
time.

### Added

- Spring Boot 3.5 and 4 apps with Spring MVC, in Java or Kotlin, from one
  `developmentOnly` dependency: each request named by its route with its controller method,
  SQL through JDBC with the line that ran each statement and each
  transaction, Logback records, errors with their source (an exception that
  is a 4xx answer isn't one), the panel's badge on HTML pages and the
  console at `/__tracepanel__/`.
- Calls to HTTP APIs through `RestClient`, `RestTemplate` and `WebClient`,
  with a `traceparent` the called app links its trace by.
- Redis commands through Lettuce and MongoDB commands, with their N+1 and
  duplicates.
- `@Async` tasks linked to the request that queued them, `@Scheduled` jobs
  and `CommandLineRunner`s and `ApplicationRunner`s as jobs.
- The agent downloaded the first time from its npm platform package,
  checked against the checksum the SDK was built with, and kept in the
  user's cache.
- Calls through the JDK's `HttpClient`: its beans, or one wrapped with
  `HttpCalls.wrap(client)`.
- Kafka and RabbitMQ: each message sent a span with its N+1, each received
  a task trace delivered from the trace that sent it.
- Kotlin apps, named Kotlin with Kotlin's version: a suspend handler's
  queries are in its request's trace on whichever thread its coroutine
  resumes.
- A handler that answers later (a `Callable`, a `DeferredResult`, a suspend
  function) is one trace until the answer, its page with the widget.
- Ktor 3 apps: `install(Tracepanel)` traces each call by its route, with
  its handler as a span of the same name and its coroutines' work on any
  thread, its errors, its logs and the widget
  on its pages; `TracepanelClient` records the client's calls, and
  `Jdbc.dataSource(ds)` a DataSource's SQL. On in Ktor's development mode.
- `EXPLAIN` of a recorded query from the console, run by the app on its own
  database connection, in the agent's own console too.
- A trace keeps at most 2,000 spans and 1,000 log records, as the agent
  stores it, and counts the rest.
- A failed request's answer carries its trace's headers, an async one keeps
  its route, and a framework's wrapper of an error already recorded isn't
  recorded again.
- The signed-in user on each trace, as `user.id` and `user.username`, as
  the Python SDK names them. Spring Boot reads the principal from Spring
  Security when the app has it. `Tracepanel.setUser(id, username)` names
  the user from any code, and `call.tracepanelUser(id, username)` in Ktor.
  The username stays on this machine.
