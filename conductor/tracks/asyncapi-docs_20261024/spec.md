# Spec — AsyncAPI docs for inventory-events (ADR 0010)

## Overview
inventory-service publishes `StockReserved`/`StockReservationFailed` events on
the `inventory-events` topic via its outbox + Debezium CDC connector (no
direct Kafka producer). This track wires the same `purekafka`/
`sbt-purekafka-docs` AsyncAPI documentation order-service already has
(`asyncapi-docs_20261017`, `sbt-asyncapi-plugin_20261017`), so
inventory-service serves a generated AsyncAPI spec + static HTML docs for its
own published events, closing the inventory-service portion of
`gluon/PLAN.md` Rework 3's "Adopt across..." bullet.

## Functional Requirements
- Add tapir `Schema[A]` derivation (`Schema.derived`) to `StockReservedEvent`/
  `StockReservationFailedEvent` in `StockEvents.scala`, alongside their
  existing circe `Codec`.
- New `AsyncApiChannels.scala` (object extending `AsyncApiChannelsProvider`),
  registering both event types on topic `inventory-events` with
  `ChannelDirection.Publish(PublisherKind.Cdc)` — mirroring order-service's
  `AsyncApiChannels.scala` doc-comment convention (CDC, not a direct producer
  call).
- Wire `sbt-purekafka-docs`: `project/plugins.sbt` adds
  `addSbtPlugin("io.github.beckfordp" % "sbt-purekafka-docs" % "<pinned
  version>")` with the GitHub Packages resolver/credentials block (copy
  order-service's `project/plugins.sbt` verbatim); `build.sbt` enables
  `PureKafkaDocsPlugin` and sets
  `asyncApiChannelsClass`/`asyncApiTitle`/`asyncApiVersion`.
- `Main.scala`: mount `purekafka.docs.Docs.routes[F]` for the raw spec at
  `/docs/asyncapi.json`, merged with existing OpenAPI routes; add a sibling
  route serving the generated static HTML at `/docs/asyncapi` (adapt
  `generate-kafka-asyncapi-docs.sh`/`verify-kafka-asyncapi-docs.sh` from
  order-service, own port).
- README: short "View Kafka AsyncAPI docs" section, same style as
  order-service's.

## Non-Functional Requirements
- Bump `io.github.beckfordp:purekafka` to the pinned version that includes
  the AsyncAPI topic-merge fix (same version order-service uses), matching
  this repo's existing re-pin convention.
- No change to inventory-service's existing publish mechanism (outbox table,
  Debezium connector config) — this is docs-generation only, purely
  additive.

## Acceptance Criteria
- `sbt compile` produces a real `target/asyncapi-docs-spec/asyncapi.json`
  (both event types, correct topic) and `target/asyncapi-docs/index.html`.
- `GET /docs/asyncapi.json` on a running instance returns 200 with both
  event types.
- `GET /docs/asyncapi` serves the static HTML.
- `sbt test` green, `scalafmtCheck` clean.
- `./scripts/verify-kafka-asyncapi-docs.sh` (adapted) passes end-to-end.

## Out of Scope
- Stream supervision / dead-letter routing (TD-2.3/TD-2.4) — inventory-service
  has no Kafka consumer, not applicable.
- Changing the outbox/Debezium publish mechanism itself.
- payment-service / notification-service's own AsyncAPI adoption — separate
  tracks, separate repos.
