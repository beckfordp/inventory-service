# TD-24 — quantityReserved ledger-only and non-negative

## Overview
Inventory's reserved counter drifted from the `order_reservations` ledger (−126 across 7 SKUs,
two negative; gluon TECHNICAL_DEBT.md TD-24 findings). Root cause: PUT/PATCH `/inventorys/{id}`
accept an absolute `quantityReserved`, so clients (acceptance teardown, gshop stock ±1) overwrote
live reservations with stale values. Reserve/release/ship are sound. Fix: only ledger operations
change reserved; the DB forbids negative quantities.

## Functional Requirements
- FR1: PUT/PATCH `/inventorys/{id}` body is `{quantityAvailable}`; a sent `quantityReserved` is
  ignored (unknown field) — 200, stored reserved unchanged.
- FR2: Negative `quantityAvailable` → 400 `quantityAvailable must not be negative`; unknown id → 404
  (unchanged).
- FR3: Store update (in-memory + Postgres) writes `quantity_available` only.
- FR4: Migration V8 adds `CHECK (quantity_available >= 0)` and `CHECK (quantity_reserved >= 0)`.
- FR5: OpenAPI docs (route descriptions, `UpdateInventoryRequest` schema, 400 text) state reserved
  is owned by the ledger and a sent value is ignored.
- FR6: gluon `specs/inventory.yaml` gets a comment on `quantityReserved` noting the hand-edit (no
  generator change).

## Non-Functional Requirements
- Backward compatible: old clients sending both fields still get 200.
- V8 requires non-negative rows; local cluster reset first (gluon `bin/reset-order-data`, added
  outside this track).

## Acceptance Criteria
- Unit/route/migration tests cover FR1–FR4; full suite, coverage, scalafmtCheck green.
- Live: `verify-order-reservations.sh` (incl. new TD-24 checks) and `verify-rest-docs.sh` pass
  against the deployed build.
- After `bin/seed-demo-data` + `bin/verify-admin-demo`, no SKU has `quantity_reserved` ≠ sum of its
  active ledger lines.

## Out of Scope
- gshop stock adjust and acceptance teardown (own tracks in those repos).
- `bin/reset-order-data` and TECHNICAL_DEBT.md (gluon, after archive).
- A generator visibility for ledger-only fields.
- Lost updates on `quantityAvailable` itself.
