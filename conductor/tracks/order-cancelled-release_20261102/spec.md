# Specification: US-9.4 — release reservations on `OrderCancelled`

## Overview

inventory-service has never consumed Kafka. Journey 5's expiry path and US-9's
shared cancellation path both need the ledger released when order-service
publishes `order-events: OrderCancelled`. The existing
`InventoryStore.releaseOrder` is already idempotent, so this track is mostly
consumer wiring.

## Functional Requirements

- FR-1: a new `order-events` consumer, group `inventory-service-order-events`,
  decodes the shared envelope and acts only on `OrderCancelled`.
- FR-2: a valid `orderId` calls `releaseOrder`; a repeated event is a no-op
  (release is idempotent); unknown/invalid ids are logged.
- FR-3: malformed envelopes/payloads and processing errors use the existing
  purekafka DLQ/retry/supervisor pattern (DLQ `order-events-dlq`).
- FR-4: `KafkaConfig` (bootstrap servers, DLQ retry/resilience, supervisor) is
  loaded from `application.conf`, env-overridable.
- FR-5: AsyncAPI registers the `OrderCancelled` subscription; the
  `asyncApiGenerate` empty-dir override is removed now there is a channel.

## Non-Functional Requirements

- `sbt scalafmtCheck test` and `schemaRegistryCheck` green; no new events
  published (inventory still publishes nothing).

## Acceptance Criteria

- A synthetic `OrderCancelled` releases the order's reserved quantity.
- A duplicate event releases nothing further.
- Malformed input dead-letters; unrelated `order-events` types are ignored.

## Out of Scope

Order-service/reason, payment/fulfilment/notification fan-out, US-21 races.
