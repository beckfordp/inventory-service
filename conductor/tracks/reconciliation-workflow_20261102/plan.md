# Plan: ADM-4 — reconciliation workflow

## Phase 1: Persistence and domain

- [x] Task: `MigrationsSuite` assertions + `V7` migration (Red → Green).
- [x] Task: `ReconciliationStatus`/`Reconciliation` pure rule + tests.
- [x] Task: Conductor - User Manual Verification 'Persistence'
  (Protocol in workflow.md) — prompting off: committed suites.

## Phase 2: Store

- [x] Task: Failing in-memory/Postgres list + resolve tests; implement both
  interpreters and the insert-only counter.
- [x] Task: Conductor - User Manual Verification 'Store'
  (Protocol in workflow.md) — prompting off: committed suites.

## Phase 3: Routes and config

- [x] Task: `OperatorAuth` + config + startup warning.
- [x] Task: Failing routes suite; add `ReconciliationRoutes`, register in
  `AppRoutes`, add `Authorization` to CORS.
- [x] Task: Update stubs, docs suite, config suite and verify-rest-docs map;
  run `sbt scalafmtCheck test`.
- [x] Task: Conductor - User Manual Verification 'Routes'
  (Protocol in workflow.md) — prompting off: full suite green (173 passed).

## Review

- Reviewed 2026-11-02 (conductor:review): plan compliance yes; style pass;
  `path[UUID]` validation, idempotent resolve, dev-token gate, CORS fixed.
  Full suite 173 passed. No blocking findings.
