# Tech Stack — inventory-service

## Language / runtime
- Scala 3.9.0
- JDK 21

## Effects / HTTP
- Cats Effect 3.7.0
- http4s 0.23.37 (ember-server)
- circe 0.14.16
- CORS — `org.http4s.server.middleware.CORS` (ships in `http4s-server`, no new
  dependency) wraps every response (`Cors.scala`). Allow all origins, methods
  limited to this service's five CRUD routes, `Content-Type` header allowed,
  credentials disallowed. Ported from `pure-service-generator`'s
  `cors_20261010` track (TD-1.3).

## API layer
- tapir 1.11.25 — route definitions and generated Swagger/OpenAPI docs
  (`purerest.docs.Docs`), served at `/docs`, kept in sync by construction
- purekafka.docs (`AsyncApiChannel`/`Docs`) — AsyncAPI documentation wiring
  for this service's Kafka event contracts (`AsyncApiChannels`), served at
  `/docs/asyncapi.json`. Since TD-7.5 retired the only two events, the
  channel list is empty and build-time spec/HTML generation is disabled via
  a `build.sbt` override (the AsyncAPI validator rejects a document with no
  channels); the raw route still serves.
- `schemaRegistryCheck` (sbt-purekafka-docs, Rework 5 step 3/ADR 0003) —
  reuses the same `AsyncApiChannels` registrations to check/register each
  event's JSON Schema against Apicurio Registry, run explicitly (not
  chained after `compile`): locally against the shared OrbStack instance
  (`schemaRegistryUrl`'s default), in CI against a throwaway
  `docker run` Apicurio container (`SCHEMA_REGISTRY_URL` env override —
  see `ci.yml`). Build/CI-time only, zero runtime presence.

## Persistence
- skunk 1.0.0 — pure-FP, non-blocking Postgres access (runtime queries)
- Flyway 11.8.2 (+ flyway-database-postgresql) — schema migrations on startup
- postgresql JDBC 42.7.13 — Flyway-only (runtime scope), never used directly
  in application code

## Config
- pureconfig 0.17.10 — typed config from `application.conf`

## Shared platform library
- `purerestlib` 0.1.0 (`io.github.beckfordp`) — tracing, structured logging,
  metrics, resilience (retry + circuit breaker), tapir-based docs. Consumed
  as a published GitHub Packages artifact, never vendored/`.dependsOn`.
  ADR 0005 flags a possible future migration off GitHub Packages (JitPack or
  Maven Central) — not yet adopted.

## Messaging
- None — TD-7.5 (2026-10-27) removed the last Kafka client (fs2-kafka) along
  with the stock events. Only `purekafka.docs` remains (AsyncAPI
  documentation, empty channel list). Reintroduce fs2-kafka if an inventory
  event is ever published again.

## Testing
- munit 1.3.6 + munit-cats-effect 2.2.1
- log4cats-testing 2.8.0 — assert on structured log output
- testcontainers-scala 0.43.6 (postgresql + munit modules) — real,
  ephemeral Postgres for integration tests, no manual local setup
- scalafmt (default Scala 3 style) — `sbt scalafmtCheck test` run in CI

## Packaging / local deploy
- sbt-native-packager (`JavaAppPackaging`, `DockerPlugin`)
- Docker image: `eclipse-temurin:21-jre`
- Docker Compose — local Postgres

## Not yet in `build.sbt`
- No Redis client needed — `gluon/docs/system-design.md`'s services table
  lists inventory-service's Redis column as `—`

## Target infrastructure (platform-wide, from `gluon/docs/system-design.md`)
- Local: OrbStack Kubernetes (see ADR 0002)
- Promotion: dev → staging → prod, all on AWS EKS, single AWS account /
  multi-namespace (see ADR 0004)
- CI/CD: GitHub Actions, candidate release = container image tag promoted
  through environments with automated smoke-test gates
