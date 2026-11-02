# Specification: Checkout Journey 7 — shipped ledger guard

## Overview

inventory-service currently cannot tell a shipped order from an active
reservation: it consumes only `OrderCancelled` and releases on every such
event. A cancel that wins the order-level race can therefore release stock for
goods that have already shipped. This track adds a `shipped` ledger state,
consumes `DispatchConfirmed`, and records reconciliation when the two sides
contradict.

## Functional Requirements

- Consume `fulfilment-events: DispatchConfirmed` (consumer group
  `inventory-service-fulfilment-events`, DLQ + supervisor; do **not** also
  consume `DispatchFailed` — order-service already owns that release).
- Ledger statuses become `active | released | shipped`.
  - `markShipped(orderId)`: under the per-order advisory lock, active lines
    become `shipped`; `quantity_reserved` decreases by their quantity and
    `quantity_available` is unchanged (it was already decremented at reserve).
  - `releaseOrder(orderId)` returns an outcome (`released` lines and any
    `shipped` lines) and releases only `active` lines.
  - `reserveOrder` keeps refusing when no line is active; a replay after
    shipping returns `ReservationReleased` (409), with wording covering
    released or shipped.
- `OrderCancelled` releases active lines; if any line was shipped it flags
  `cancel_after_dispatch`.
- `DispatchConfirmed` ships active lines; if any line was already released it
  flags `dispatch_after_release`.
- `inventory_reconciliation(order_id uuid primary key references
  order_reservations(order_id) on delete cascade, reason, created_at)`;
  first-wins `flagReconciliation`/`reconciliationReason`.
- `GET /inventorys/order-reservations` accepts `status=shipped`; descriptions
  updated. `AsyncApiChannels` documents the new subscription; the stale
  AsyncAPI verify scripts are updated.

## Acceptance Criteria

- Store contract tests (in-memory + Postgres): ship active, duplicate ship,
  ship after release, release after ship, reserve after ship, status filter,
  aggregate quantities.
- Consumer tests: dispatch ships; duplicate dispatch; dispatch after release
  flags; malformed payload → DLQ; unrelated event ignored; cancel after
  shipped flags.
- Concurrent release vs ship yields one consistent ledger and one flag.
- Migration suite asserts `shipped` is accepted and the reconciliation table
  exists.
- `sbt scalafmtCheck test` green; `schemaRegistryCheck`/AsyncAPI scripts updated.

## Out of Scope

gshop/admin mock `shipped` parity (J7 gshop track), ADM-1 UI changes.
