# Inventory review fixes — Implementation Plan

## Phase 1 — Pure core (H3, M1, M2, L2, L3) [checkpoint: 68ea806]

- [x] Task: Write failing tests: overflow demand rejected + ScalaCheck property; `FailureReason.fromWire`; `StockLevels` create/update validation; confirm failure. `991cfb6` — red: compile failures + overflow assertion
- [x] Task: Implement: move `OrderReservation.scala` to `domain/`; `Long` demand; `FailureReason` enum + `fromWire`; shared UUID parser; `domain.StockLevels`. `991cfb6` — also moved `InventoryError` to `domain/` and dropped its dead cases (L1)
- [x] Task: Conductor - User Manual Verification 'Phase 1 — Pure core' (Protocol in workflow.md) — prompting off: covered by suite (133 green)

## Phase 2 — Store and routes (H1, H2, M1, L1, L4–L8) [checkpoint: fd135e1]

- [x] Task: Write failing tests: duplicate sku → `Left` (both interpreters) and `409`; invalid create/update → `400`; list ordered by sku; confirm failure. `986898a`
- [x] Task: Implement: `create` returns `Either[DuplicateSku, Inventory]`; validation wired into routes; exhaustive rejection logging; `serverEndpoints` list; `UUIDGen`/narrowed in-memory bound; drop dead config/error cases; Cors comment. `986898a` — deviation: store's `reserveOrder` now returns the narrower `ReservationConflict` (Rejected | Released), which is what makes the logging match exhaustive; test-only `TestStores.seed` keeps 45 fixtures one call
- [x] Task: Conductor - User Manual Verification 'Phase 2 — Store and routes' (Protocol in workflow.md) — prompting off: covered by suite (140 green, incl. Postgres testcontainers); live run in Phase 3

## Phase 3 — Verification

- [x] Task: Extend `scripts/verify-order-reservations.sh` (duplicate sku 409, invalid quantity 400, overflow reserve 409); full suite + coverage + scalafmtCheck; run the script live. `6be788a` — 140 green; 92.21% stmt / 94.05% branch; script passed live
- [x] Task: Conductor - User Manual Verification 'Phase 3 — Verification' (Protocol in workflow.md) — prompting off: covered by `verify-order-reservations.sh` (all checks passed)
