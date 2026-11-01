# Spec: REST docs convention (gluon TD-20.6)

## Overview
inventory-service `/docs` shows bare paths and unexplained schemas. Apply the
TD-20 convention (purerest README "Documenting endpoints"), mirroring
order-service `rest-docs_20261101`, and assert it in a test.

## Functional Requirements
- FR1: purerestlib 0.4.0 → 0.7.3; suite green; tech-stack.md synced.
- FR2: Per-endpoint error outputs — already narrowed by TD-16.2
  (`inventory-review-fixes_20261101`): POST 400 (invalid body) + 409
  (duplicate sku); GET list none; GET by id 404; PATCH/PUT 400 + 404;
  DELETE 404; reserve order 400 + 409 (already released) + 409 (rejected
  lines); release order none; health 200 only; ready 503. Pin with an
  `InventoryDocsSuite` test walking each endpoint's `errorOutput` statuses.
- FR3: Every endpoint `.summary`/`.description`/`.tag` (`inventorys` for
  CRUD, `order-reservations` for the two bulk routes, `health`) — see
  Appendix.
- FR4: `id`/`orderId` path params and the optional `sku` query param
  described, with examples. No header inputs.
- FR5: Every request/response DTO and field `@description`; every DTO class
  an `@encodedExample` (or equivalent body example).
- FR6: Every error variant described with when it happens and an example
  body; the fixed message values are shared between the example and the
  tapir mapping.
- FR7: `InventoryDocsSuite`: `Docs.undocumented` over the same 10 endpoints
  `AppRoutes.endpoints` passes to `Docs.routes` returns `Nil`.
- FR8: `scripts/verify-rest-docs.sh` checks the served `/docs/docs.yaml`
  for summaries/descriptions/examples and the narrowed error statuses.

## Non-Functional
- Wire format unchanged (encode/decode identical); scalafmtCheck clean.
- Examples use UUID-shaped ids (ids are server-minted or order-service
  supplied) and the real SKU convention `watch-<brand>-<model-slug>`; no
  real ids, credentials or secrets.
- `PATCH`/`PUT` keep full-replace semantics and the `quantityReserved`
  admin override; the docs state both caveats (TD-16.2 M3/L10).

## Acceptance Criteria
- Full suite green, `Docs.undocumented == Nil`, verify script PASS, CI green.
- `/docs` describes every served endpoint, parameter, DTO field and error.

## Out of Scope
- Behaviour changes; fixing the M3 ledger drift or L10 PATCH semantics (only
  documented here); AsyncAPI side (TD-19).

## Appendix: proposed descriptions (NOT applied — awaiting user approval)

> These are proposals only. No `.summary`/`.description`/`.tag`,
> `@description`, `@encodedExample` or `.example` has been applied to code.

### Endpoints (10 served, including health)
| Endpoint | summary | description | tag |
|---|---|---|---|
| POST /inventorys | Create inventory | Creates a stock record for a SKU; `quantityReserved` starts at 0. Body is validated first: blank `sku` or a negative quantity → 400. SKU is unique: an existing SKU → 409. Not idempotent — each successful call creates a new record. | inventorys |
| GET /inventorys | List inventory | Stock records ordered by SKU. Optional exact `sku` filter; an unknown filter returns `[]`. Never 404s. | inventorys |
| GET /inventorys/{id} | Get inventory | One stock record by id. 404 for an unknown id; a malformed UUID is rejected by the path decoder as 400. | inventorys |
| PATCH /inventorys/{id} | Update inventory | Sets both quantities; every field is required. Idempotent. Admin override: writes `quantityReserved` directly and does not touch the `order_reservations` ledger, so it can drift from the active lines (a later release may then drive it negative). Body is checked first (400), then existence (404). | inventorys |
| PUT /inventorys/{id} | Update inventory (replace) | Same as PATCH — both fields required, same admin-override caveat; kept for PUT clients. | inventorys |
| DELETE /inventorys/{id} | Delete inventory | Hard-deletes the record; repeat → 404. Past reservation ledger lines for the SKU are left untouched. | inventorys |
| POST /inventorys/order-reservations | Reserve stock for an order | All-or-nothing bulk reservation, idempotent per `orderId`. Validates the body (UUIDs, non-empty `items`, positive `quantity`, no duplicate `orderItemId`) → 400. Reserves every line in one transaction, summing demand per SKU in `Long`, or reserves nothing and returns 409 listing every failed line. An order that still holds active lines returns its original lines and reserves nothing more (201); a released order returns 409 rather than reporting stock it no longer holds. No event is published. | order-reservations |
| POST /inventorys/order-reservations/{orderId}/release | Release an order's reservation | Returns every active line to `quantityAvailable` and marks its ledger lines released. Always 204: unknown, already-released or non-UUID `orderId` holds nothing, so compensation is safe to retry. No event is published. | order-reservations |
| GET /health | Liveness check | 200 while the process is up. | health |
| GET /health/ready | Readiness check | 200 when Postgres answers a ping, else 503. | health |

### Parameters
- `id` (path, InventoryId): "Inventory record id (UUID)", example
  `3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90`.
- `orderId` (path, release): "Order id; any string is accepted and a
  non-UUID is a no-op", example `5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f`.
- `sku` (query, list): "Exact SKU to filter by", example
  `watch-rolex-submariner`.
- No header inputs.

### DTO fields and examples
- **CreateInventoryRequest** — example
  `{"sku":"watch-rolex-submariner","quantityAvailable":25}`
  - `sku`: "Unique stock-keeping unit code", example `watch-rolex-submariner`
  - `quantityAvailable`: "Units currently available, >= 0", example `25`
- **UpdateInventoryRequest** — example
  `{"quantityAvailable":25,"quantityReserved":3}`
  - `quantityAvailable`: "Units currently available, >= 0", example `25`
  - `quantityReserved`: "Units held by reservations, >= 0. Admin override:
    written directly, bypassing the reservation ledger", example `3`
- **InventoryResponse** — example
  `{"id":"3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90","sku":"watch-rolex-submariner","quantityAvailable":25,"quantityReserved":3,"createdAt":"2026-11-01T12:00:00Z","updatedAt":"2026-11-01T12:05:00Z"}`
  - `id`: "Inventory record id (UUID)", example
    `3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90`
  - `sku`: "Unique stock-keeping unit code", example `watch-rolex-submariner`
  - `quantityAvailable`: "Units currently available", example `25`
  - `quantityReserved`: "Units held by active reservations", example `3`
  - `createdAt`: "UTC creation timestamp", example `2026-11-01T12:00:00Z`
  - `updatedAt`: "UTC last-update timestamp", example `2026-11-01T12:05:00Z`
- **ErrorResponse** — example `{"error":"Inventory not found"}`
  - `error`: "Human-readable reason", example `Inventory not found`
- **ReserveOrderRequest** — example
  `{"orderId":"5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f","items":[{"orderItemId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","sku":"watch-rolex-submariner","quantity":2}]}`
  - `orderId`: "Order id (UUID); the idempotency key", example
    `5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f`
  - `items`: "Lines to reserve, at least one; all-or-nothing", example one
    line as above
- **ReserveOrderItemRequest** — example
  `{"orderItemId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","sku":"watch-rolex-submariner","quantity":2}`
  - `orderItemId`: "order-service line id (UUID)", example
    `a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d`
  - `sku`: "SKU to reserve", example `watch-rolex-submariner`
  - `quantity`: "Units to reserve, > 0", example `2`
- **OrderReservationResponse** — example
  `{"orderId":"5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f","items":[{"orderItemId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","sku":"watch-rolex-submariner","quantity":2}]}`
  - `orderId`: "Order id (UUID)", example
    `5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f`
  - `items`: "Every line the order now holds", example one line as above
- **ReservationLineResponse** — example
  `{"orderItemId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","sku":"watch-rolex-submariner","quantity":2}`
  - `orderItemId`: "order-service line id (UUID)", example
    `a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d`
  - `sku`: "SKU held", example `watch-rolex-submariner`
  - `quantity`: "Units held", example `2`
- **ReservationRejectedResponse** — example
  `{"error":"Insufficient stock","failures":[{"orderItemId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","sku":"watch-rolex-submariner","reason":"insufficient_stock"}]}`
  - `error`: "Human-readable summary; always `Insufficient stock`", example
    `Insufficient stock`
  - `failures`: "Every failed line, in request order", example one line as
    above
- **ReservationFailureResponse** — example
  `{"orderItemId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","sku":"watch-rolex-submariner","reason":"insufficient_stock"}`
  - `orderItemId`: "Failed line id (UUID)", example
    `a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d`
  - `sku`: "SKU that could not be reserved", example
    `watch-rolex-submariner`
  - `reason`: "`insufficient_stock` (summed demand exceeds available) or
    `unknown_sku` (no stock record)", example `insufficient_stock`

### Error variants
| Status | When | Example body |
|---|---|---|
| 400 | Create: blank `sku` | `{"error":"sku must not be blank"}` |
| 400 | Create/update: negative `quantityAvailable` | `{"error":"quantityAvailable must not be negative"}` |
| 400 | Update: negative `quantityReserved` | `{"error":"quantityReserved must not be negative"}` |
| 404 | GET/PATCH/PUT/DELETE: no record with that id | `{"error":"Inventory not found"}` |
| 409 | Create: SKU already has a stock record | `{"error":"Inventory already exists for this sku"}` |
| 400 | Reserve: `orderId`/`orderItemId` not a UUID | `{"error":"orderId must be a UUID"}` |
| 400 | Reserve: `items` empty | `{"error":"items must not be empty"}` |
| 400 | Reserve: line `quantity` <= 0 | `{"error":"quantity must be positive"}` |
| 400 | Reserve: repeated `orderItemId` | `{"error":"duplicate orderItemId"}` |
| 409 | Reserve: one or more lines can't be reserved | `{"error":"Insufficient stock","failures":[{"orderItemId":"a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d","sku":"watch-rolex-submariner","reason":"insufficient_stock"}]}` |
| 409 | Reserve: the order's reservation was already released | `{"error":"Order reservation already released"}` |
| 400 | GET/PATCH/PUT/DELETE: malformed path UUID (tapir decoder, not `ErrorResponse`) | tapir decode-failure body |
| 503 | Ready: Postgres did not answer the ping | no body |
