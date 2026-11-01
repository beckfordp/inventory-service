# Spec: inventory-service review fixes (TD-16.2)

## Overview
Full review of inventory-service main + test code (baseline `387d725`, 123 tests
green) against `code_styleguides/scala-fp.md` (Review checklist) and `general.md`.
Fix every High and Medium finding and the cheap Lows. No wire-contract change: the
bulk reserve/release request/response bodies order-service's `InventoryClient`
uses stay byte-identical, and every existing 2xx/4xx body keeps its shape.

## Findings

### High
- **H1 `case other` over a sealed type.** `OrderReservationRoutes.reserveOrderServerEndpoint`
  logs a rejection via `case other => "error" -> other.toString` over sealed
  `OrderReservationError`; a new case wouldn't break compilation. → exhaustive match.
- **H2 Duplicate sku is an untyped 500, and the rule lives only in SQL.**
  `POST /inventorys` for an existing sku raises the Postgres unique-constraint
  exception (500); the in-memory store accepts the duplicate (interpreters disagree,
  and a later bulk reserve then shifts both rows). → `create` returns
  `Either[DuplicateSku, Inventory]` in both interpreters (Postgres: `UniqueViolation`
  → `Left`); route maps it to `409 {"error": ...}`.
- **H3 Int overflow in the all-or-nothing check.** `ReserveOrder.failures` sums
  per-sku demand in `Int`: two lines of one sku at `Int.MaxValue` sum to `-2`, pass
  the stock check, and the stock shift then *adds* to `quantityAvailable`. → sum
  demand in `Long`; ScalaCheck property that demand above stock is always rejected.

### Medium
- **M1 CRUD input never validated.** `POST` accepts a blank sku and negative
  `quantityAvailable`; `PATCH`/`PUT` accept negative quantities. The unused
  `InvalidQuantity` case shows it was intended. → pure `domain.StockLevels` smart
  constructors (parse once at the boundary) → `400 {"error": ...}`.
- **M2 Pure core outside `domain/`.** `ReserveOrder`/`failures`/`FailureReason`
  live in the root package beside effectful code. → move to `inventoryservice.domain`.
- **M3 (deferred)** `PATCH`/`PUT` write `quantityReserved` directly, bypassing the
  reservation ledger, so `quantity_reserved` can drift from the active ledger lines
  and a later release can drive it negative. Fixing it means dropping or rejecting
  the field — a wire-contract change, out of scope. Documented as an admin override
  in TD-20.6's endpoint descriptions; raise as a follow-up.

### Low
- **L1** Dead `InventoryError` cases `InsufficientStock`/`InvalidQuantity` (unused
  since TD-7.5). → replaced by the M1/H2 cases.
- **L2** UUID parsing repeated inline (`ReserveOrder.parseUuid`, release route,
  `fromRejectedResponse`). → one `domain` parser reused.
- **L3** `FailureReason` wire lookup enumerates cases by hand. → Scala 3 `enum`
  with `fromWire` over `values`.
- **L4** The served endpoint list is written twice (`AppRoutes` and
  `InventoryRoutes.routes`); create's server endpoint is named `serverEndpoint`.
  → one `InventoryRoutes.serverEndpoints`, `createInventoryServerEndpoint`.
- **L5** `InventoryStore.inMemory` takes `Sync` and both stores mint ids via
  `Sync.delay(UUID.randomUUID())`. → `UUIDGen`; in-memory narrowed to
  `Concurrent: Clock: UUIDGen`.
- **L6** Dead `KafkaConfig`/`kafka.bootstrap-servers` (no Kafka since TD-7.5). → removed.
- **L7** `GET /inventorys` has no `ORDER BY`: list order is arbitrary and differs
  between interpreters. → ordered by sku in both.
- **L8** Stale `Cors` doc comment ("freshly generated service"). → fixed.
- **L9 (skipped)** `outbox_events` table (V3) has had no writer since TD-7.5.
  Dropping it needs a migration and a Debezium connector check; out of scope.
- **L10 (skipped)** `PATCH` has full-replace semantics, same as `PUT`; changing it is
  a contract change. Documented in TD-20.6.

No resource/cancellation findings: both bulk paths run in `session.transaction.use`
(rollback on error/cancel), behind a per-order advisory lock and sku-ordered row locks.

## Acceptance Criteria
- Each fixed finding has a failing-first test, now green.
- Bulk reserve/release wire format unchanged (existing route suites + live
  `verify-order-reservations.sh` green).
- Full suite + scalafmtCheck green; CI green.

## Out of Scope
M3, L9, L10 (above); TD-20.6 REST docs (next track).
