# Implementation Plan: add GET /inventorys list/filter-by-sku endpoint

## Phase 1: Add list endpoint end-to-end [checkpoint: be581b9]

- [x] Task: Write failing tests (`InventoryStoreSuite`, `InventoryStorePostgresSuite`, `InventoryRoutesSuite`) for `list`: no-filter returns all rows, sku filter returns just that row or empty, route-level 200 + logging assertions `40d91ab`
- [x] Task: Add `InventoryStore.list` to the trait + both implementations; add `GET /inventorys` to `InventoryRoutes.scala`, wire into `routes()` and `Main.scala`'s explicit endpoint list; update two pre-existing stub `InventoryStore` implementations in `HealthRoutesSuite` forced by the new trait method `40d91ab`
- [x] Task: Add `scripts/verify-list-endpoint.sh`, run full suite (85/85 passing) `be581b9`
- [x] Task: Conductor - User Manual Verification 'add GET /inventorys list/filter-by-sku endpoint' (final, Protocol in workflow.md) `be581b9`

Single phase: a small, well-scoped addition (one new trait method, one new
endpoint) with no natural phase boundary — commits `40d91ab`
(implementation) and `be581b9` (verify script) are the dividing line
instead.
