# TD-24 Implementation Plan

Note: the local cluster already has V8 applied (from a pre-track build, now stashed); the V8 file
must keep the name `V8__inventory_quantities_nonnegative.sql` and identical content, or Flyway
validation fails on redeploy.

## Phase 1 — Ledger-only stock update [checkpoint: e0b4cc7]
- [x] Task: Update takes `quantityAvailable` only (FR1–FR3) — 4c9a6aa
  - [x] Red: `StockLevelsSuite` (update(avail) only; negative → InvalidInventory); `InventoryRoutesSuite` PATCH/PUT ignore a sent `quantityReserved` (200, reserved unchanged) and negative available → 400; adjust `TestStores.setLevels`, store suites, existing `UpdateInventoryRequest(100, 0)` callers. Confirm failure.
  - [x] Green: `StockUpdate`/`StockLevels.update`, `UpdateInventoryRequest`, route handler, in-memory + Postgres update SQL.
  - [x] Refactor; coverage.
- [x] Task: OpenAPI docs state ledger ownership (FR5) — route/schema descriptions, example, 400 text; `verify-rest-docs.sh` still passes. — e51c724 (verify-rest-docs: 86 OK; 1 pre-existing FAIL from ADM-4 — reconciliation `Authorization` header has no example; out of scope)
- [x] Task: Conductor - User Manual Verification 'Phase 1 — Ledger-only stock update' (Protocol in workflow.md) — covered by tests; live checks in Phase 3

## Phase 2 — Non-negative constraints [checkpoint: cd56f46]
- [x] Task: V8 CHECK constraints (FR4) — b93f38c
  - [x] Red: `MigrationsSuite` — inserting negative available or reserved fails; zero succeeds. Confirm failure.
  - [x] Green: `V8__inventory_quantities_nonnegative.sql`.
- [x] Task: Conductor - User Manual Verification 'Phase 2 — Non-negative constraints' (Protocol in workflow.md) — covered by MigrationsSuite; live in Phase 3

## Phase 3 — Live verification
- [x] Task: `verify-order-reservations.sh` step 10 — negative available → 400; sent reserved ignored, levels unchanged. — 06bfd94 (also fixed stale J7 released-error expectation; 36 OK)
- [x] Task: Full suite + coverage + scalafmtCheck; deploy (`bin/k8s-local-up inventory-service`, cluster already reset); run `verify-order-reservations.sh`, `verify-rest-docs.sh`, `bin/seed-demo-data`, `bin/verify-admin-demo`; drift SQL → 0 rows. — no code change (176/176, 84.2%/86.3%, scalafmt clean; deployed, Flyway accepted V8; seed + admin demo PASS; drift 0 rows, reserved 2 = active 2; live PATCH {quantityReserved:99} left reserved 1)
- [~] Task: gluon `specs/inventory.yaml` comment (FR6) — committed in gluon.
- [ ] Task: Conductor - User Manual Verification 'Phase 3 — Live verification' (Protocol in workflow.md)
