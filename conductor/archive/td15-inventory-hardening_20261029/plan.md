# TD-15.4 Implementation Plan

## Phase 1 — Typed ids
- [x] Write failing tests: `InventoryIdSuite` + malformed route id → 400; confirm failure. — df2e27f
- [x] Implement `domain/InventoryId`; update `Inventory`, store, routes; replace `Try(UUID.fromString)` in reservation code with `Either.catchOnly`. — df2e27f
- [x] Refactor: update fixtures/suites. — df2e27f
- [x] Task: Conductor - User Manual Verification 'Phase 1 — Typed ids' (Protocol in workflow.md) — covered by tests/live scripts

## Phase 2 — Config, ScalaCheck, narrowing
- [x] Add `asyncApiHtmlDir` config/`AppRoutes`/`Main`; no `sys.env`. — 50ce979
- [x] Add `munit-scalacheck` + properties; narrow `Cors`/`HealthRoutes`/route handlers. — 50ce979
- [x] Task: Conductor - User Manual Verification 'Phase 2 — Config & purity' (Protocol in workflow.md) — covered by tests/live scripts

## Phase 3 — Verification
- [x] Full suite + coverage + scalafmt; live scripts. — 123 tests, 91.0%/93.7% coverage; `verify-kafka-asyncapi-docs.sh` + `verify-order-reservations.sh` passed
- [x] Task: Conductor - User Manual Verification 'Phase 3 — Verification' (Protocol in workflow.md) — covered by tests/live scripts
