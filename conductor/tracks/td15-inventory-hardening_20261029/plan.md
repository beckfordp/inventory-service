# TD-15.4 Implementation Plan

## Phase 1 — Typed ids
- [ ] Write failing tests: `InventoryIdSuite` + malformed route id → 400; confirm failure.
- [ ] Implement `domain/InventoryId`; update `Inventory`, store, routes; replace `Try(UUID.fromString)` in reservation code with `Either.catchOnly`.
- [ ] Refactor: update fixtures/suites.
- [ ] Task: Conductor - User Manual Verification 'Phase 1 — Typed ids' (Protocol in workflow.md)

## Phase 2 — Config, ScalaCheck, narrowing
- [ ] Add `asyncApiHtmlDir` config/`AppRoutes`/`Main`; no `sys.env`.
- [ ] Add `munit-scalacheck` + properties; narrow `Cors`/`HealthRoutes`/route handlers.
- [ ] Task: Conductor - User Manual Verification 'Phase 2 — Config & purity' (Protocol in workflow.md)

## Phase 3 — Verification
- [ ] Full suite + coverage + scalafmt; live scripts.
- [ ] Task: Conductor - User Manual Verification 'Phase 3 — Verification' (Protocol in workflow.md)
