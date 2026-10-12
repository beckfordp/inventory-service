# Plan — TD-3.3: Transactional outbox for inventory-service (ADR 0008)

## Phase 1 — Outbox table + atomic reserve write
- [x] Task: Write failing `MigrationsSuite` test asserting `outbox_events` exists with columns `id`/`aggregate_id`/`payload`/`trace_parent`/`correlation_id`/`created_at` (Red) `ffd8d5e`
- [x] Task: Add `V3__create_outbox_events.sql` migration to make it pass (Green) `ffd8d5e`
- [ ] Task: Write failing `InventoryStoreSuite` test: on a successful `reserve`, a matching outbox row (`aggregate_id` = `orderItemId`) is written in the same transaction as the conditional UPDATE (Red)
- [ ] Task: Extend `InventoryStore.reserve`'s success path (needs `orderItemId` threaded in as a new parameter) to write the outbox row inside its existing transaction (Green)
- [ ] Task: Write failing test: on `InsufficientStock`, a matching `StockReservationFailed` outbox row is written, with no inventory row changed (Red); implement as a standalone outbox INSERT on that path (Green)
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Outbox table + atomic reserve write' (Protocol in workflow.md)

## Phase 2 — Wire InventoryRoutes to the outbox, not the publisher
- [ ] Task: `InventoryRoutes`' `publishOutcome`: replace both `publisher.publishReserved(...)` and `publisher.publishFailed(...)` calls with the new `reserve` outbox-writing behavior from Phase 1 (Red: route test asserts outbox row rather than publisher invocation; Green: wire it)
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Wire InventoryRoutes to the outbox' (Protocol in workflow.md)

## Phase 3 — Delete StockEventPublisher
- [ ] Task: Delete `StockEventPublisher.scala` and `StockEventPublisherSuite.scala`
- [ ] Task: Remove `StockEventPublisher.resource(...).use { ... }` wiring and publisher parameter threading from `Main.scala` and `InventoryRoutes`
- [ ] Task: Run full test suite, confirm no regressions, confirm nothing still references `StockEventPublisher`
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Delete StockEventPublisher' (Protocol in workflow.md)
