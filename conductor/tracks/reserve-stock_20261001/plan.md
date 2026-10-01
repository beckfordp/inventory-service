# Plan — reserve-stock_20261001

## Phase 1: Schema — unique constraint on sku
- [ ] Task: Write failing test asserting a duplicate-sku insert violates a unique
      constraint (new test in `InventoryStorePostgresSuite.scala` or a dedicated
      `MigrationsSuite` case)
- [ ] Task: Add Flyway migration `V2__add_inventory_sku_unique_constraint.sql`
      (`ALTER TABLE "inventory" ADD CONSTRAINT inventory_sku_unique UNIQUE (sku)`)
- [ ] Task: Run tests, confirm green
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Schema' (Protocol in
      workflow.md)

## Phase 2: Domain error + store method
- [ ] Task: Write failing unit tests for `InventoryStore.reserve(sku, quantity)`
      against the in-memory store: success (decrements/increments correctly),
      insufficient stock, unknown sku
- [ ] Task: Add `InsufficientStock` case object to `InventoryError`
- [ ] Task: Add `reserve(sku: String, quantity: Int): F[Either[InventoryError,
      Inventory]]` to the `InventoryStore` trait; implement in
      `InventoryStore.inMemory`
- [ ] Task: Run tests, confirm green

## Phase 3: Postgres implementation
- [ ] Task: Write failing `InventoryStorePostgresSuite` tests for `reserve`: success
      case, insufficient-stock case (no mutation), unknown-sku case, and a
      concurrent-reservations-don't-oversell case (fire concurrent `reserve` calls
      summing to more than available, assert total reserved never exceeds original
      available)
- [ ] Task: Implement `reserve` in `InventoryStore.postgres` using the single
      conditional `UPDATE ... WHERE sku = $sku AND quantity_available >= $qty
      RETURNING ...`; on 0 rows affected, follow up with a `SELECT` by sku to
      distinguish not-found vs. insufficient-stock
- [ ] Task: Run tests, confirm green

## Phase 4: HTTP endpoint
- [ ] Task: Write failing `InventoryRoutesSuite` tests for `POST
      /inventorys/reservations`: 200 + `InventoryResponse` on success, 409 + error
      body on insufficient stock, 404 + error body on unknown sku
- [ ] Task: Add `ReserveInventoryRequest(sku: String, quantity: Int)` DTO + codec
- [ ] Task: Add the `reserveInventoryEndpoint`/`reserveInventoryServerEndpoint`
      (tapir `PublicEndpoint`, `POST /inventorys/reservations`, `errorOut` mapping
      `InsufficientStock→409` and reusing the existing `notFoundOutput` for
      `InventoryNotFound→404`)
- [ ] Task: Wire the new server endpoint into `InventoryRoutes.routes` and `Main`'s
      `docsRoutes` list
- [ ] Task: Run tests, confirm green
- [ ] Task: Verify coverage (`sbt coverage test coverageReport`, target >80% on new
      code)
- [ ] Task: Conductor - User Manual Verification 'Phase 4: HTTP endpoint' (Protocol
      in workflow.md)
