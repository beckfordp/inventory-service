# Spec — reserve-stock_20261001

## Overview
Add a synchronous "reserve stock" endpoint to inventory-service so order-service can,
during checkout, atomically check and reserve stock for a SKU without overselling
(US-4.1).

## Functional Requirements
- New endpoint: `POST /inventorys/reservations`, body `{sku: String, quantity: Int}`.
- Looks up the inventory row by `sku` (not the internal UUID `id` used by existing CRUD
  endpoints).
- Schema: new Flyway migration `V2__add_inventory_sku_unique_constraint.sql` adding
  `ALTER TABLE "inventory" ADD CONSTRAINT inventory_sku_unique UNIQUE (sku)` — makes
  sku-based lookup well-defined (today's schema allows duplicate skus).
- Atomic reserve via a single conditional `UPDATE`:
  ```sql
  UPDATE "inventory"
  SET quantity_available = quantity_available - $qty,
      quantity_reserved = quantity_reserved + $qty,
      updated_at = now()
  WHERE sku = $sku AND quantity_available >= $qty
  RETURNING id, quantity_available, quantity_reserved, created_at, updated_at
  ```
  Postgres row-level locking makes the check-and-update atomic under concurrent
  requests for the same sku — no app-level transaction/lock needed.
- If the `UPDATE` affects 0 rows, a follow-up `SELECT` by `sku` disambiguates *why*: no
  such sku (404) vs. insufficient stock (409).
- Success: `200 OK` + full `InventoryResponse` (existing DTO) reflecting
  post-reservation state.
- Sku not found: `404`, reuses existing `InventoryNotFound` error case/mapping.
- Insufficient stock: `409 Conflict`, new `InsufficientStock` case added to the
  `InventoryError` sealed trait, own `errorOut` mapping (own JSON error body, e.g.
  `{"error": "Insufficient stock"}`).

## Non-Functional Requirements
- Concurrency-safe under concurrent reservation requests for the same sku (proven by
  the conditional UPDATE's row-level locking, tested with concurrent requests that
  together exceed available stock).

## Acceptance Criteria
- Valid sku + quantity ≤ available → `200`, `quantityAvailable` decremented,
  `quantityReserved` incremented by the requested amount.
- quantity > available → `409`, no mutation (verified via a follow-up GET showing
  unchanged values).
- Unknown sku → `404` (`InventoryNotFound`).
- Concurrent reservations against the same sku never collectively oversell (sum of
  reserved never exceeds the original available).
- `sbt scalafmtCheck test` passes; Testcontainers-backed integration test covers the
  new endpoint end-to-end.

## Out of Scope
- Publishing `inventory.stock-reserved` / `inventory.stock-reservation-failed` Kafka
  events — that's US-5.1, a separate track.
- Releasing/un-reserving stock (compensating action if a downstream step fails) — not
  requested by US-4.1.
- Wiring resilience middleware on the caller side — order-service's job (US-4.2).
