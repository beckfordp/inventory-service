# Specification: ADM-1 — reservation-ledger read endpoint

## Overview
The admin console needs to show reservation state and cancellations releasing
lines. Expose the existing `order_reservations` ledger read-only.

## Requirements
- `GET /inventorys/order-reservations?orderId=&status=`.
- Rows: orderId, orderItemId, lineNo, sku, quantity, status, reservedAt,
  releasedAt.
- Filters valid: UUID orderId, `active`/`released`; invalid -> 400.
- Read-only; no event. Both store interpreters + tests.
