# Changelog

## 0.4.0 (2026-10-21)

The SDK downloads `tracepanel-agent` 0.4.0 the first time: an app says what it
offers the console in its hello, and the console shows its tasks in flight.

### Added

- A form's POST that redirects is in the panel of the page it leads to,
  as for Python: a redirect (Spring's `redirect:`, `sendRedirect`, a 3xx
  `ResponseEntity`; Ktor's `respondRedirect`) that answers the browser's
  navigation sets the `tracepanel_redirect` cookie, which names it, and
  the redirects before it, for 30 seconds. The widget lists them before
  the page. A fetch() that follows a redirect sets none.
- A query no code of the app's made has a `code.caller`, as for Python:
  the library code that asked for it, past the ORM, its repositories, the
  pool and the driver (Jackson writing a lazy relation, Spring Security's
  `JdbcUserDetailsManager` loading a user). The console shows it, marked
  as a library's. Code is the app's by its packages, as for `code.origin`.
- Ktor: the session (the Sessions plugin's) is in the trace, as Spring's
  `HttpSession` is: each session's properties under `session.*`, their
  secrets masked. A deferred session (`io.ktor.server.sessions.deferred`)
  is not read for it.
- The console's Live on the Tasks page (Now) for Spring apps, as for
  Celery, RQ and Dramatiq ones: task events tell the agent what is queued
  and what runs before its trace exists. An `@Async` method's run is
  queued from its `task.enqueue` on, then runs (system `spring`); an
  `@Scheduled` method's runs (`scheduled`); a message a `@KafkaListener` or
  a `@RabbitListener` handles runs (`kafka`, `rabbitmq`), queued from its
  publish when a `KafkaTemplate` or a `RabbitTemplate` in a trace sent it,
  with the same id on both sides. Each app instance is a worker,
  `service@host`, with a heartbeat every 30 seconds while it is up, so a
  task whose process went leaves the list. A record or a message that
  failed counts its attempts when it comes again. The hello says
  `live_tasks` once the app has any of them.
- The console's Request tab for Spring and Ktor requests, as for Python
  ones: a trace holds its request's headers, query, cookies and body, the
  session (Spring), and the answer's headers, under the same names and
  with the same masks. A body is what the app read itself, up to 16 KB: a
  JSON body with the values under a secret's name masked at any depth, a
  form with its fields and its files by name and size. A body the app
  didn't read is not read for it. `TRACEPANEL_CAPTURE_REQUEST=0` leaves it
  all out.
- The console's Profile tab: a request with an `X-Tracepanel-Profile: 1`
  header, or the cookie of the panel's Profile button, is profiled. A
  daemon thread samples the stack of the thread running it every
  millisecond and follows it across threads: an async request's dispatch, a
  coroutine on `Dispatchers.IO`. Each function is `Class.method`, your code
  told from the libraries'. `TRACEPANEL_PROFILE=1` profiles every trace,
  `TRACEPANEL_PROFILE_ENABLED=0` none, and `TRACEPANEL_PROFILE_INTERVAL_MS`
  sets the interval. The hello tells the agent both, so the console offers
  them.
- A streamed HTML page gets the panel too. In Spring, that is a page the
  app flushes before it ends, or one past the container's buffer, such as
  a `StreamingResponseBody`. In Ktor, it is a page written as it is made:
  `respondHtml`, `respondTextWriter`, `respondBytesWriter`, a channel. The
  script goes in right after the `<head>` (or `<body>`) opens, as the pieces
  pass, and nothing else is held: only a tag cut between two pieces waits
  for its end. A streamed page also gets the badge's headers, with the time
  so far. A compressed page, or one that declares its size, is left as it
  is.
- Log4j 2: in an app that logs with it (`spring-boot-starter-log4j2`, or
  `log4j-core` in a Ktor app), each record made during a request or a task
  goes to its trace, as Logback's do: the level (`FATAL` is `critical`), the
  logger, the message and its template, the line that logged it, the MDC
  (`ThreadContext`) as `extra.*` attributes, secrets masked, and the
  exception with its stack. It is installed once per process, and stays
  through a reconfiguration, such as Spring Boot's own after startup. A
  record made through an async logger is not in a trace: it is appended on
  another thread.
- Logback: a record's MDC is in the trace too, as `extra.*` attributes.

### Changed

- Spring and Ktor no longer hold a streamed HTML page whole until it ends:
  it goes out as the app writes it.
- What the app offers the console is said once, in the hello to the agent:
  its service, and EXPLAIN (answered at the app's own console) once it has
  a database. The console's `api/capabilities` comes from the agent, which
  offers EXPLAIN only through the app's console. The app's console no longer
  answers it itself, and connects to the agent first, so the agent has heard
  the hello.

### Fixed

- A compressed HTML page (one with a `Content-Encoding`) is no longer read
  as text for the widget, which broke it.
- A second app in the same process that installs Tracepanel (two Ktor
  servers) no longer records each log twice.
- The first trace after the agent restarted was lost (the panel said
  "Trace not found"): the old connection still took the write on this side.
  The SDK now watches the connection for its end, and connects again
  before the next batch.

## 0.3.2 (2026-10-19)

The SDK downloads `tracepanel-agent` 0.3.7 the first time.

### Added

- The agent comes from Maven Central (`dev.tracepanel:tracepanel-agent`),
  or from the mirror of it that `~/.m2/settings.xml` names, with its
  server's login. A company that mirrors Maven Central with Artifactory or
  Nexus needs nothing more. `TRACEPANEL_MAVEN_REPOSITORY` names another
  repository. When the repository has no agent, it comes from npm, as
  before, or from the npm mirror that `TRACEPANEL_NPM_REGISTRY` names.
- The download goes through a company proxy: `HTTPS_PROXY` and `NO_PROXY`,
  as curl and npm read them, else the JVM's own proxy settings. A login
  goes in the proxy's URL.

### Changed

- The SDK checks the agent's binary against its checksum, not the npm
  package it came in, so the check is the same from Maven Central, a
  mirror or npm. A repository's login stays off the host it redirects to.
- A failed download says where it read from, and what to set: a proxy, a
  mirror or `TRACEPANEL_AGENT_BIN`.

## 0.3.1 (2026-10-18)

### Changed

- Spring Boot: Tracepanel turns on in development only, with a `dev`,
  `development` or `local` profile or with Spring Boot DevTools on the
  classpath. It was on wherever the dependency was, so one that reached a
  production build recorded every request and served the console there.
  `TRACEPANEL_ENABLED=1` turns it on anyway. Without a profile or DevTools,
  add one, or set the variable.
- The console on the app's origin, EXPLAIN included, answers this machine
  only. It answered reads from anywhere. `TRACEPANEL_CONSOLE_REMOTE=1` opens
  it, as an app in a container needs.

### Fixed

- Secrets are masked before they are recorded, as in the Python SDK: an
  outgoing call's URL loses its `user:password@` and the values of query
  parameters such as `api_key` or `token`, and a SQL parameter bound to a
  column such as `password` or `token` reads `********`.

## 0.3.0 (2026-10-18)

The first release. The SDK downloads `tracepanel-agent` 0.3.4 the first
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
