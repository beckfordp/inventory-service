# Plan — inventory-service: REST docs convention (gluon TD-20.6)

## Phase 1: purerestlib 0.7.3
- [x] Task: bump `purerestlibVersion` 0.4.0 → 0.7.3 (+ aligned pins); fix API drift until suite green; sync `tech-stack.md` — other pins already aligned, no API drift; 140/140 (incl. Postgres Testcontainers), scalafmtCheckAll green. `PENDING_SHA`
- [x] Task: Conductor - User Manual Verification 'Phase 1: purerestlib 0.7.3' (Protocol in workflow.md) — prompting off: covered by suite

## Phase 2: Per-endpoint error outputs
- [ ] Task: `InventoryDocsSuite` walks each endpoint's `errorOutput` statuses (FR2 subsets) — already narrowed by TD-16.2; this pins it
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Per-endpoint error outputs' (Protocol in workflow.md) — prompting off: covered by suite

## Phase 3: Descriptions and examples (only after the Appendix is approved)
- [ ] Task: coverage test first in `InventoryDocsSuite` (`Docs.undocumented(AppRoutes.endpoints) == Nil`, red) → apply the approved Appendix summaries/descriptions/tags, param descriptions/examples, DTO annotations + examples, error descriptions + examples; green — red prepared locally: 69 gaps, test left uncommitted pending Appendix approval
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Descriptions and examples' (Protocol in workflow.md) — prompting off: covered by suite

## Phase 4: Smoke test
- [ ] Task: `scripts/verify-rest-docs.sh` — run service, fetch `/docs/docs.yaml`, assert summaries/descriptions/examples + narrowed errors; PASS
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Smoke test' (Protocol in workflow.md) — prompting off: covered by `scripts/verify-rest-docs.sh`
