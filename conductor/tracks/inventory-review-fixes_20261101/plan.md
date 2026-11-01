# Inventory review fixes — Implementation Plan

## Phase 1 — Pure core (H3, M1, M2, L2, L3)

- [ ] Task: Write failing tests: overflow demand rejected + ScalaCheck property; `FailureReason.fromWire`; `StockLevels` create/update validation; confirm failure.
- [ ] Task: Implement: move `OrderReservation.scala` to `domain/`; `Long` demand; `FailureReason` enum + `fromWire`; shared UUID parser; `domain.StockLevels`.
- [ ] Task: Conductor - User Manual Verification 'Phase 1 — Pure core' (Protocol in workflow.md)

## Phase 2 — Store and routes (H1, H2, M1, L1, L4–L8)

- [ ] Task: Write failing tests: duplicate sku → `Left` (both interpreters) and `409`; invalid create/update → `400`; list ordered by sku; confirm failure.
- [ ] Task: Implement: `create` returns `Either[DuplicateSku, Inventory]`; validation wired into routes; exhaustive rejection logging; `serverEndpoints` list; `UUIDGen`/narrowed in-memory bound; drop dead config/error cases; Cors comment.
- [ ] Task: Conductor - User Manual Verification 'Phase 2 — Store and routes' (Protocol in workflow.md)

## Phase 3 — Verification

- [ ] Task: Extend `scripts/verify-order-reservations.sh` (duplicate sku 409, invalid quantity 400, overflow reserve 409); full suite + coverage + scalafmtCheck; run the script live.
- [ ] Task: Conductor - User Manual Verification 'Phase 3 — Verification' (Protocol in workflow.md)
