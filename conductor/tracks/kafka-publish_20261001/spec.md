# Spec — kafka-publish_20261001

## Overview
Add Kafka event publishing to inventory-service so order-service can (eventually, via
US-5.2) asynchronously react to reservation outcomes without the existing sync
reserve-stock call depending on Kafka (US-5.1).

## Functional Requirements
- New Kafka producer using **fs2-kafka**. Two topics: `inventory.stock-reserved`,
  `inventory.stock-reservation-failed` (already named in
  `gluon/docs/system-design.md`).
- Event payload (same shape for both topics), plain JSON via circe:
  `{inventoryId: String, sku: String, quantity: Int, timestamp: Instant}`. No schema
  registry (ADR 0003's format/impl choice stays an open, flagged gap — not resolved by
  this track).
- Publish `inventory.stock-reserved` when `InventoryStore.reserve` returns
  `Right(entity)` (quantity = amount just reserved).
- Publish `inventory.stock-reservation-failed` **only** when it returns
  `Left(InsufficientStock)` — not for `InventoryNotFound`/`InvalidQuantity`
  (caller-input errors, not stock outcomes).
- Wired into `InventoryRoutes.reserveInventoryServerEndpoint`, right after
  `store.reserve`, same place structured logging already happens.
- The HTTP response **awaits** the publish call (bounded timeout), but a publish
  failure (e.g. broker down) is logged and never changes the HTTP response already
  determined by `store.reserve` — the existing sync reserve-stock behavior must stay
  identical to before this track.
- `docker-compose.yml` gets a `kafka` service for local dev; tests get a
  Testcontainers Kafka module dependency.

## Non-Functional Requirements
- Bounded timeout around the publish call — a down/slow Kafka can never hang the sync
  HTTP response indefinitely.
- Plain JSON, no Avro/Protobuf/registry — consistent with this service's existing
  JSON-everywhere convention; ADR 0003's registry decision stays open platform-wide.

## Acceptance Criteria
- Success → exactly one message on `inventory.stock-reserved` with correct
  `inventoryId`/`sku`/`quantity`.
- `409` (`InsufficientStock`) → exactly one message on
  `inventory.stock-reservation-failed` with the same fields.
- `404`/`400` → no message on either topic.
- Kafka unreachable → endpoint still returns its normal HTTP result, within a bounded
  time (no indefinite hang).
- `sbt scalafmtCheck test` passes; a Testcontainers-Kafka-backed integration test
  proves the publish side end-to-end (not that anything downstream consumes it, per
  `PLAN.md`'s Phase 3 note).

## Out of Scope
- Schema registry/Avro/Protobuf integration (ADR 0003 follow-up).
- order-service's consumer side (US-5.2 — separate repo/track).
- Kafka topic compaction/partitioning strategy (deferred platform-wide).
- Custom retry/outbox pattern beyond fs2-kafka's own producer defaults.
