# Overview
Add an `orderItemId` correlation id to the reserve-stock REST contract and
its two downstream Kafka events, fixing the cross-order misattribution risk
found while designing order-service's US-5.2 consumer. Order-service mints
the id (its own `order_items.id`) and sends it on each reserve call;
inventory-service treats it as an opaque string — stores nothing new, just
echoes it onto the `stock-reserved`/`stock-reservation-failed` event it
already publishes.

# Functional Requirements
- `ReserveInventoryRequest` gains a required `orderItemId: String` field
  (same validation style as `sku`/`quantity` — missing/invalid JSON -> 400
  via existing decode-failure mapping, no new explicit validation needed)
- `StockReservedEvent` and `StockReservationFailedEvent` each gain
  `orderItemId: String`, populated from the request field, propagated
  unchanged on both success and failure
- No change to `InventoryStore.reserve` or inventory-service's own DB
  schema — purely a pass-through field on the API boundary and event
  payload
- Implements against `gluon/docs/system-design.md`'s already-updated REST +
  Kafka contracts (updated in a prior step), not the other way around

# Non-Functional Requirements
- `orderItemId` is never persisted in inventory-service's own DB, held only
  for the duration of the single request
- All existing call sites of `ReserveInventoryRequest`/`StockReservedEvent`/
  `StockReservationFailedEvent` (routes, publisher, tests) need updating for
  the new required field

# Acceptance Criteria
- [ ] `POST /inventorys/reservations` with `orderItemId` -> 200/404/409
  behave exactly as before, unaffected by the new field
- [ ] `POST /inventorys/reservations` missing `orderItemId` -> 400 (decode
  failure)
- [ ] A successful reservation publishes `inventory.stock-reserved` with
  `orderItemId` matching the request
- [ ] A failed (insufficient stock) reservation publishes
  `inventory.stock-reservation-failed` with `orderItemId` matching the
  request
- [ ] `sbt scalafmtCheck test` passes

# Out of Scope
- Any change to inventory-service's own DB schema or `InventoryStore`
- order-service's consumer-side changes (separate track, order-service repo)
- Validating `orderItemId`'s format (e.g. must be a UUID) — treated as an
  opaque string
