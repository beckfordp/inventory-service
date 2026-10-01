# Plan — reserve-stock_20261001

Plan approved at commit: b99718c (`chore(inventory-service): add reserve-stock track`)
— every commit below is in addition to that baseline; diff from there to review all
track work as a whole.

## Phase 1: Schema — unique constraint on sku [checkpoint: 7760ba0]
- [x] Task: Write failing test asserting a duplicate-sku insert violates a unique
      constraint (new test in `InventoryStorePostgresSuite.scala` or a dedicated
      `MigrationsSuite` case) [5a1de70]
- [x] Task: Add Flyway migration `V2__add_inventory_sku_unique_constraint.sql`
      (`ALTER TABLE "inventory" ADD CONSTRAINT inventory_sku_unique UNIQUE (sku)`)
      [5a1de70]
- [x] Task: Run tests, confirm green [5a1de70]
- [x] Task: Conductor - User Manual Verification 'Phase 1: Schema' (Protocol in
      workflow.md) [7760ba0]

## Phase 2: Domain error + store method [checkpoint: 194766e]
- [x] Task: Write failing unit tests for `InventoryStore.reserve(sku, quantity)`
      against the in-memory store: success (decrements/increments correctly),
      insufficient stock, unknown sku [194766e]
- [x] Task: Add `InsufficientStock` case object to `InventoryError` [194766e]
- [x] Task: Add `reserve(sku: String, quantity: Int): F[Either[InventoryError,
      Inventory]]` to the `InventoryStore` trait; implement in
      `InventoryStore.inMemory` (the `postgres` implementation gets a temporary
      `???` stub, satisfied for real in Phase 3) [194766e]
- [x] Task: Run tests, confirm green [194766e]

## Phase 3: Postgres implementation [checkpoint: fb4f423]
- [x] Task: Write failing `InventoryStorePostgresSuite` tests for `reserve`: success
      case, insufficient-stock case (no mutation), unknown-sku case, and a
      concurrent-reservations-don't-oversell case (fire concurrent `reserve` calls
      summing to more than available, assert total reserved never exceeds original
      available) [fb4f423]
- [x] Task: Implement `reserve` in `InventoryStore.postgres` using the single
      conditional `UPDATE ... WHERE sku = $sku AND quantity_available >= $qty
      RETURNING ...`; on 0 rows affected, follow up with a `SELECT` by sku to
      distinguish not-found vs. insufficient-stock [fb4f423]
- [x] Task: Run tests, confirm green [fb4f423]

## Phase 4: HTTP endpoint [checkpoint: pending]
- [x] Task: Write failing `InventoryRoutesSuite` tests for `POST
      /inventorys/reservations`: 200 + `InventoryResponse` on success, 409 + error
      body on insufficient stock, 404 + error body on unknown sku [69a0e35]
- [x] Task: Add `ReserveInventoryRequest(sku: String, quantity: Int)` DTO + codec
      [69a0e35]
- [x] Task: Add the `reserveInventoryEndpoint`/`reserveInventoryServerEndpoint`
      (tapir `PublicEndpoint`, `POST /inventorys/reservations`, `errorOut` mapping
      `InsufficientStock→409` and reusing the existing `notFoundOutput` for
      `InventoryNotFound→404`) [69a0e35]
- [x] Task: Wire the new server endpoint into `InventoryRoutes.routes` and `Main`'s
      `docsRoutes` list [69a0e35]
- [x] Task: Run tests, confirm green [69a0e35]
- [x] Task: Verify coverage (`sbt coverage test coverageReport`, target >80% on new
      code) — 90.48% statement / 91.49% branch overall [69a0e35]
- [ ] Task: Conductor - User Manual Verification 'Phase 4: HTTP endpoint' (Protocol
      in workflow.md)
