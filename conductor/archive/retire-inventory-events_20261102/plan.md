# Plan: TD-22 — retire inventory-events producer plumbing

## Phase 1: Inventory service

- [x] Task: Failing migration test — outbox table absent after V5 (Red)
- [x] Task: V5 drop migration; remove outbox test; update README/product/tech-stack (Green)
- [x] Task: Replace verify script with infrastructure-absent checks
- [x] Task: Conductor - User Manual Verification 'Inventory service' (Protocol in workflow.md)

## Phase 2: Infra + acceptance + docs

- [x] Task: Connector config/job DELETE step; remove cleanup CronJob; update local-up
- [x] Task: Acceptance precheck/unit test/README
- [x] Task: system-design, diagrams, ADR 0008 amendment, PLAN PERF-5.4
- [x] Task: Conductor - User Manual Verification 'Infra + acceptance + docs' (Protocol in workflow.md)

## Review (2026-11-02)

- `sbt scalafmtAll scalafmtCheckAll test` green (147 tests); V5 drop
  migration and the outbox-table-absent test pass.
- Live cluster: connector absent, topic absent, CronJob absent, outbox table
  absent; `verify-inventory-events-retired.sh` PASS.
- No blocking findings.
