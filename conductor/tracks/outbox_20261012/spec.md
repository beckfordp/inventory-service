# Spec — TD-3.3: Transactional outbox for inventory-service (ADR 0008)

## Overview
inventory-service's sync reserve endpoint (`POST /inventorys/reservations`)
calls `store.reserve(...)` (Postgres) and then, as a separate step, calls
`StockEventPublisher` to produce directly to Kafka — the code's own
comment already acknowledges this publish can fail and is only logged,
not retried (TD-3). This track closes that gap by writing an
`outbox_events` row instead of calling a producer. Also fixes a found
drift: `StockEventPublisher` currently keys `inventory-events` records by
`sku`; `system-design.md` says the partition key should be `orderItemId`.
The outbox's `aggregate_id` will hold `orderItemId`, correcting this going
forward.

## Functional Requirements
1. New Flyway migration (`V3__create_outbox_events.sql`): `outbox_events`
   table — `id` (uuid, pk), `aggregate_id` (text, not null — holds
   `orderItemId`, not `sku`), `payload` (jsonb, not null — the complete
   `EventEnvelope` JSON), `trace_parent` (text, nullable), `correlation_id`
   (text, not null — also `orderItemId`, unchanged from today),
   `created_at` (timestamptz, not null, default `now()`). No
   `sent`/`processed` column.
2. **Success path** (`StockReserved`): `InventoryStore.reserve` is changed
   to write the matching outbox row **inside the same transaction** as
   its conditional UPDATE — true atomicity, closing the actual dual-write
   hazard.
3. **Failure path** (`StockReservationFailed`, on `InsufficientStock`):
   unlike the success path, `reserve` makes **no** DB state change here
   (the conditional UPDATE affects 0 rows) — so there's nothing to be
   atomic *with*. This is simply a standalone outbox INSERT replacing the
   direct publish call, still removing the same-class risk (a
   lost/failed produce call), just without a co-located state change to
   anchor it to.
4. `StockEventPublisher` (the trait, its Kafka-producer `Resource`,
   `noOp`) is deleted outright, along with `StockEventPublisherSuite.scala`.
5. `Main.scala` wiring updated: the `StockEventPublisher.resource(...).use
   { ... }` block and its threading through to `InventoryRoutes` removed.
6. Test coverage (Testcontainers-Postgres): success path asserts the
   outbox row (`aggregate_id` = `orderItemId`, decodable `payload`,
   `correlation_id`) is written atomically with the reservation UPDATE;
   failure path asserts the outbox row is written on `InsufficientStock`
   with no inventory row changed.

## Non-Functional Requirements
- No live Kafka/Kafka Connect/Debezium needed for this track's tests.
- No change to `POST /inventorys/reservations`'s HTTP response shape or
  status codes.

## Acceptance Criteria
- `outbox_events` table exists per the shape above, keyed by
  `orderItemId`.
- Both call sites in `publishOutcome` (success and failure) write to the
  outbox instead of calling the publisher.
- `StockEventPublisher` and its test suite are gone.
- Atomicity test for the success path passes; failure-path outbox-insert
  test passes.
- Full existing test suite still passes.

## Out of Scope
- TD-3.1 (Kafka Connect + Debezium connector, gluon-root) — separate
  track.
- The Outbox Event Router SMT's own config — lives with TD-3.1.
- Outbox-row cleanup CronJob — also TD-3.1/gluon-root.
- order-service's and payment-service's own outbox migrations (TD-3.2,
  done; TD-3.4) — separate tracks in their own repos.
