# Plan: Checkout Journey 7 — shipped ledger guard

## Phase 1: Ledger shipped state

- [x] Task: Failing contract tests — ship active/decrement reserved, duplicate
  ship, ship after release, release after ship, reserve after ship, filter
  `shipped` (Red). (59720b4)
- [x] Task: Migration `V6__add_shipped_reservation_status.sql`; extend the
  in-memory ledger and Postgres store with `markShipped`, release outcomes,
  shipped status derivation and the reconciliation table/methods (Green). (59720b4)
- [x] Task: Migration suite.
- [x] Task: `sbt scalafmtCheck test`.

## Phase 2: Dispatch consumer and handlers

- [x] Task: Failing handler tests for cancel-after-ship and dispatch-after-
  release (Red). (59720b4)
- [x] Task: Add `FulfilmentEventConsumer` (DispatchConfirmed), DLQ/supervisor
  wiring, AsyncAPI channel; update `OrderCancelledConsumer` to use the release
  outcome and flag `cancel_after_dispatch` (Green). (59720b4)
- [x] Task: Concurrent release vs ship test.
- [x] Task: Update reservation route filter/descriptions and verify scripts.
- [x] Task: `sbt scalafmtCheck test`, `schemaRegistryCheck`/AsyncAPI checks.
- [x] Task: Conductor - User Manual Verification 'Shipped ledger guard'
  (Protocol in workflow.md)

## Review

- Reviewed 2026-11-02: plan compliance yes; style pass; tests cover shipping
  aggregates, duplicate ship, release-after-ship, reserve-after-ship, filters,
  cancel/dispatch flags, malformed DLQ, release-vs-ship race and migrations.
  Full suite 168 passed; `schemaRegistryCheck` and the generated AsyncAPI spec
  (both subscriptions) green. No blocking findings.
