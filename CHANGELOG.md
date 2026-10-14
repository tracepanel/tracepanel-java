# Changelog

## Unreleased

The first release, 0.2.0, with the agent of the same version.

### Added

- Spring Boot 3.5 and 4 apps with Spring MVC, from one `developmentOnly`
  dependency: each request named by its route with its controller method,
  SQL through JDBC with the line that ran each statement and each
  transaction, Logback records, errors with their source, the panel's badge
  on HTML pages and the console at `/__tracepanel__/`.
- Calls to HTTP APIs through `RestClient`, `RestTemplate` and `WebClient`,
  with a `traceparent` the called app links its trace by.
- Redis commands through Lettuce and MongoDB commands, with their N+1 and
  duplicates.
- `@Async` tasks linked to the request that queued them, `@Scheduled` jobs
  and `CommandLineRunner`s and `ApplicationRunner`s as jobs.
- The agent downloaded the first time from its npm platform package,
  checked against the checksum the SDK was built with, and kept in the
  user's cache.
- Kotlin apps are named Kotlin, with Kotlin's version.
