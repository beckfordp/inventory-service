# Overview
Add a `GET /inventorys` list/filter-by-sku endpoint, driven by gshop's
`admin-screen_20261010` track needing to list inventory rows and look up a
row's internal id by sku (previously only possible via `GET /inventorys/{id}`
with the internal UUID, which gshop has no way to discover on its own).
Omitted `sku` query param returns every row; a present `sku` filters to just
that row (empty list if none match).

# Functional Requirements
- New `InventoryStore.list(sku: Option[String]): F[List[Inventory]]` on the
  trait, implemented for both `inMemory` (Map filter) and `postgres` (two
  skunk queries: select-all vs. select-by-sku)
- New `GET /inventorys` tapir endpoint with an optional `sku` query
  parameter, returning `List[InventoryResponse]`
- Wired into both the test-only `routes()` aggregator and `Main.scala`'s
  explicit server-endpoint list

# Non-Functional Requirements
- No change to existing endpoints' behavior or schema
- Logging follows this service's existing method/path/context conventions

# Acceptance Criteria
- [x] `GET /inventorys` with no `sku` returns every row
- [x] `GET /inventorys?sku=...` returns only the matching row (empty list if
  none match)
- [x] Existing `InventoryStore` implementations (in-memory, postgres) and two
  other test-only stub implementations (`HealthRoutesSuite`) updated for the
  new trait method
- [x] `sbt test` passes (85/85, including testcontainers-backed Postgres
  tests)

# Out of Scope
- Pagination (today's row count is far below catalog-service's ~100-item
  scale that justified pagination there)
- Any change to `POST /inventorys/reservations` or the create/update/delete
  endpoints
