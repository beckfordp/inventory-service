# Specification: TD-7.5 — retire per-item reservations and stock events

## Overview

order-service switched to the bulk all-or-nothing reserve (TD-7.3) and no
longer consumes `inventory-events`. inventory-service therefore still carries
a per-item `POST /inventorys/reservations` endpoint, an
`InventoryStore.reserve` path, and two Kafka event contracts
(`StockReserved`/`StockReservationFailed`) that nothing uses. This track
removes all of it, including the two registered schema-registry artifacts,
leaving the bulk reserve + release ledger as the only reservation surface.

## Functional Requirements

- **FR-1 — Remove the endpoint.** `POST /inventorys/reservations`,
  `ReserveInventoryRequest`, its tapir endpoint/error output and
  `reserveInventoryServerEndpoint` are deleted; the route returns 404.
  `POST /inventorys/order-reservations` and `.../{orderId}/release` are
  untouched.
- **FR-2 — Remove the store path.** `InventoryStore.reserve` (trait,
  in-memory and Postgres implementations), the `reserveInventory`/
  `selectIdBySku` queries and the per-item reserve tests are removed. The
  "never collectively oversell" concurrency test is retained, rewritten
  against `reserveOrder` with distinct order ids.
- **FR-3 — Remove the events.** `StockReservedEvent`/
  `StockReservationFailedEvent` (`StockEvents.scala`) and the outbox writes
  for reserve outcomes are deleted, along with any outbox helpers left
  unused by that removal. The `outbox_events` table/migration and the
  Debezium connector config stay (inert).
- **FR-4 — Empty AsyncAPI surface.** `AsyncApiChannels.channels` becomes an
  empty list; `/docs/asyncapi.json` still serves and `schemaRegistryCheck`
  no-ops. Tests assert neither retired event type appears in the docs or the
  channel list.
- **FR-5 — Registry artifacts deleted.** Delete
  `inventory-events-StockReserved` and
  `inventory-events-StockReservationFailed` from the local Apicurio
  registry's `gluon` group; update
  `scripts/verify-schema-registry-check.sh` (also fixing its stale
  `inventory-service` group path) to assert both are absent and the check
  still exits cleanly.
- **FR-6 — Docs swept.** `gluon/docs/system-design.md` says the artifacts
  are deleted (not "will be"); inventory-service's
  `README.md`/`conductor/product.md`/`conductor/tech-stack.md` stop claiming
  it publishes stock events; order-service's stale README/tech-stack
  references to `StockEventConsumer` and `inventory-events` are corrected.
  `TECHNICAL_DEBT.md` TD-7.5 and `PLAN.md` Journey 1 row are checked off.
- **FR-7 — Tests.** Suite covers the 404 removal, keeps bulk reserve/release
  coverage, and the AsyncAPI/OpenAPI docs tests reflect the retired
  contracts. Full `scalafmtCheck test` green.

## Non-Functional Requirements

- No behavior change to bulk reserve, release, idempotency or the ledger.
- No remaining references to the retired event types or the per-item
  endpoint outside historical conductor archives/docs.
- Coverage stays >80%; scalafmt clean (CI enforces `scalafmtCheck`).
- Live check: redeployed inventory-service returns 404 for the old endpoint,
  still reserves/releases through the bulk endpoint, and the registry
  listing has no stock-event artifacts.

## Acceptance Criteria

- `sbt scalafmtCheck test` green; `schemaRegistryCheck` succeeds against a
  registry without those artifacts.
- Grep finds no live code/doc references to
  `StockReserved`/`StockReservationFailed` or
  `POST /inventorys/reservations` (archives/docs excepted).
- Live redeploy: old endpoint 404; bulk reserve/release still passes;
  Apicurio artifacts gone.
- `gluon` docs and both services' READMEs match the running system.

## Out of Scope

- Dropping the `outbox_events` table, its migration, or the inventory
  Debezium connector.
- Deleting the `inventory-events` Kafka topic.
- Rewriting historical user stories (US-4.1/US-5.1) or archived tracks.
