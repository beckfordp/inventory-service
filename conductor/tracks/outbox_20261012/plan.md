# Plan — TD-3.3: Transactional outbox for inventory-service (ADR 0008)

## Phase 1 — Outbox table + atomic reserve write [checkpoint: bdfeb8d]
- [x] Task: Write failing `MigrationsSuite` test asserting `outbox_events` exists with columns `id`/`aggregate_id`/`payload`/`trace_parent`/`correlation_id`/`created_at` (Red) `ffd8d5e`
- [x] Task: Add `V3__create_outbox_events.sql` migration to make it pass (Green) `ffd8d5e`
- [x] Task: Write failing `InventoryStoreSuite` test: on a successful `reserve`, a matching outbox row (`aggregate_id` = `orderItemId`) is written in the same transaction as the conditional UPDATE (Red) `cab4ee2`
- [x] Task: Extend `InventoryStore.reserve`'s success path (needs `orderItemId` threaded in as a new parameter) to write the outbox row inside its existing transaction (Green) `cab4ee2`
- [x] Task: Write failing test: on `InsufficientStock`, a matching `StockReservationFailed` outbox row is written, with no inventory row changed (Red); implement as a standalone outbox INSERT on that path (Green) `cab4ee2` — deviation: implemented inside the same `session.transaction` block as the success path for code simplicity, not a separate bare INSERT; no behavioral difference since there's no competing write on this path
- [x] Task: Conductor - User Manual Verification 'Phase 1: Outbox table + atomic reserve write' (Protocol in workflow.md) `bdfeb8d` — reduced-prompting mode: automated-test verification only (91 passed), no interactive walkthrough; see git note on `bdfeb8d`

## Phase 2 — Wire InventoryRoutes to the outbox, not the publisher [checkpoint: 7184fb3]
- [x] Task: `InventoryRoutes`' `publishOutcome`: replace both `publisher.publishReserved(...)` and `publisher.publishFailed(...)` calls with the new `reserve` outbox-writing behavior from Phase 1 (Red: route test asserts outbox row rather than publisher invocation; Green: wire it) `e2e2fa3` — deviation: "wiring it" meant deleting `publishOutcome` entirely, not replacing its body — `reserve` already writes the outbox row itself (Phase 1), so there's nothing left for the routes layer to do
- [x] Task: Conductor - User Manual Verification 'Phase 2: Wire InventoryRoutes to the outbox' (Protocol in workflow.md) `7184fb3` — reduced-prompting mode: automated-test verification only (91 passed), no interactive walkthrough; see git note on `7184fb3`

## Phase 3 — Delete StockEventPublisher
- [ ] Task: Delete `StockEventPublisher.scala` and `StockEventPublisherSuite.scala`
- [ ] Task: Remove `StockEventPublisher.resource(...).use { ... }` wiring and publisher parameter threading from `Main.scala` and `InventoryRoutes`
- [ ] Task: Run full test suite, confirm no regressions, confirm nothing still references `StockEventPublisher`
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Delete StockEventPublisher' (Protocol in workflow.md)
