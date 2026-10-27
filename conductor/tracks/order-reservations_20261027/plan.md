# Plan: order-reservations_20261027

## Phase 1: Ledger + store
- [x] Task: V4 migration `order_reservations` ledger `0695169`
  - [ ] Write MigrationsSuite test (table, PK, check constraints) — red
  - [ ] Add `V4__create_order_reservations.sql` — green
- [ ] Task: Domain types + `OrderReservationError` ADT
  - [ ] Tests: request validation (empty items, qty ≤ 0, duplicate orderItemId, non-UUID ids)
  - [ ] `OrderReservation.scala`: `ReservationLine`, `ReservationFailure(orderItemId, sku, reason)`, `FailureReason` (InsufficientStock|UnknownSku), errors `InvalidReservation(msg)` | `ReservationRejected(failures)`
- [ ] Task: `reserveOrder` / `releaseOrder` in-memory interpreter
  - [ ] Tests: all-or-nothing, every failing line reported, same-sku aggregation, idempotent repeat, release restores once, release no-op
  - [ ] Implement via single `Ref.modify`
- [ ] Task: `reserveOrder` / `releaseOrder` postgres interpreter
  - [ ] Testcontainers tests mirroring in-memory + concurrency (no oversell) + duplicate concurrent same-orderId
  - [ ] Implement: advisory xact lock, `FOR UPDATE ORDER BY sku`, aggregate check, updates + ledger inserts; release in one tx
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Ledger + store' (Protocol in workflow.md)

## Phase 2: REST endpoints
- [ ] Task: `POST /inventorys/order-reservations`
  - [ ] Route tests: 201 body, 409 failures list, 400 cases, idempotent 201
  - [ ] tapir endpoint + oneOf errorOut, wire into `routes` + `AppRoutes` docs
- [ ] Task: `POST /inventorys/order-reservations/{orderId}/release`
  - [ ] Route tests: 204 for active, released, unknown, non-UUID
  - [ ] Endpoint + wiring
- [ ] Task: `scripts/verify-order-reservations.sh` (real service + Postgres via docker compose)
- [ ] Task: Conductor - User Manual Verification 'Phase 2: REST endpoints' (Protocol in workflow.md)
