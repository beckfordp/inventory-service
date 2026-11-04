# Plan: Journey telemetry (inventory-service)

## Phase 1: Adopt 0.8.0 cross-cutting telemetry
- [x] Task: Bump purerestlib + purekafka 0.8.0; `Telemetry.resource` takes
      `ioRuntimeMetrics`; `Main` passes `runtime.metrics` b6c7349
- [x] Task: Write failing tests, then wrap pool(s) with `PoolMetrics`; `EventAge` in both consumers b6c7349
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)

## Phase 2: Business metrics and milestones
- [x] Task: Write failing tests for the business metrics below f38717c
- [x] Task: Implement and wire in `Main` f38717c
- [x] Task: Full `sbt scalafmtCheck test` + coverage (177 tests, InventoryJourneyMetrics 100%)
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Phase 3: Deploy + live verification
- [x] Task: Bump purerest 0.8.2 — JFR runtime telemetry off (gluon TD-28) c45c3f4
- [x] Task: Build/deploy to gluon-local; `scripts/verify-journey-telemetry.sh` greps the
      metrics port for the new series after real traffic
- [x] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)

## Phase: Review Fixes
- [x] Task: Apply review suggestions 0f5badc
