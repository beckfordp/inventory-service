# Spec: Bulk order reservations + release (TD-7.1/7.2, ADR 0018)

## Overview
Add an all-or-nothing bulk reserve and an idempotent release-by-order, backed by a
reservation ledger, per `gluon/docs/system-design.md` REST contracts
(`POST /inventorys/order-reservations`, `.../{orderId}/release`). The legacy per-item
`POST /inventorys/reservations` and its `StockReserved`/`StockReservationFailed` outbox
events stay untouched until order-service switches (its `StockEventConsumer` still
depends on them); their removal and registry artifact deletion is a follow-up cleanup
track.

## Functional Requirements
1. Migration V4: `order_reservations` ledger — `order_id UUID`, `order_item_id UUID`,
   `sku TEXT`, `quantity INT > 0`, `status TEXT ('active'|'released')`, `created_at`,
   `released_at NULL`; PK `(order_id, order_item_id)`, index on `order_id`.
2. `InventoryStore.reserveOrder(orderId, items)` — one transaction:
   `pg_advisory_xact_lock` on orderId (serialises duplicate concurrent calls); if ledger
   rows exist for orderId, return them unchanged (idempotent); else lock inventory rows
   `FOR UPDATE ORDER BY sku`, aggregate quantity per sku (same sku on two lines
   allowed), collect every failing line (`insufficient_stock` | `unknown_sku`); any
   failure rolls back with nothing reserved; else decrement available / increment
   reserved per sku and insert ledger rows as `active`.
3. `InventoryStore.releaseOrder(orderId)` — one transaction: returns each active line's
   quantity (reserved → available), marks lines `released` with `released_at`.
   Unknown or already-released orderId is a no-op.
4. `POST /inventorys/order-reservations` — `201` `{orderId, items[{orderItemId, sku,
   quantity}]}`; `409` `{error: "Insufficient stock", failures[{orderItemId, sku,
   reason}]}`; `400` `{error}` for empty items, quantity ≤ 0, duplicate orderItemId,
   non-UUID orderId/orderItemId.
5. `POST /inventorys/order-reservations/{orderId}/release` — `204` always (non-UUID
   orderId also `204`, no-op).
6. Both interpreters (inMemory + postgres); new `OrderReservationError` ADT mapped 1:1
   to tapir `errorOut`; both endpoints in OpenAPI docs.
7. Publishes nothing; `AsyncApiChannels` unchanged.

## Non-Functional Requirements
- No partial functions; TDD; >80% coverage on new code; `sbt scalafmtCheck test` before
  every commit.
- Concurrency test: two concurrent bulk reserves competing for the same last units —
  exactly one succeeds, no oversell.

## Acceptance Criteria
- Success reserves all lines and writes the ledger; failure reports every failing line
  and mutates nothing.
- Repeated reserve for the same orderId returns `201` with the original lines, no
  double reserve.
- Release restores stock once; repeated release is a `204` no-op.
- `scripts/verify-order-reservations.sh` drives the real service + Postgres end-to-end
  and passes.

## Out of Scope
- Removing the legacy endpoint, retiring stock events, deleting the
  `inventory-events-StockReserved`/`-StockReservationFailed` registry artifacts
  (cleanup track after order-service TD-7.3).
- Reservation TTL / confirm (TD-8.1), auth-expiry release consumer (US-20.3).
