# Plan: TD-7.5 — retire per-item reservations and stock events

## Phase 1: Tests to the target API

- [x] Task: Replace the five `POST /inventorys/reservations` route tests with one asserting the path is gone, and keep the bulk reserve/release suites unchanged (Red) `e084e71` (deviation: http4s returns 405, not 404 — the path falls through to `GET /inventorys/{id}` with id `reservations`; assertion updated and documented in the test)
- [x] Task: Remove `reserve` from the in-memory/Postgres store suites and their test stubs; rewrite the "never collectively oversell" test against `reserveOrder` with five distinct order ids (Red / compile-level) `e084e71`
- [x] Task: Update `AsyncApiChannelsSuite` and `InventoryAsyncApiDocsSuite` to assert the retired event types are absent (Red) `e084e71` (`InventoryDocsSuite` needed no change: it never documented the per-item endpoint)
- [x] Task: Conductor - User Manual Verification 'Tests to the target API' (Protocol in workflow.md) — `sbt scalafmtCheck test` green (120/120); the behavioral checks are exercised live in Phase 5

## Phase 2: Remove the endpoint, store path and events

- [x] Task: Delete `ReserveInventoryRequest`, its tapir endpoint/error output, `reserveInventoryServerEndpoint`, the `routes`/`AppRoutes` entries (Green) `e084e71`
- [x] Task: Delete `InventoryStore.reserve` (trait, in-memory, Postgres), the `reserveInventory`/`selectIdBySku` queries, and dead outbox helpers left without a writer (Green) `e084e71` (also removed the now-unused `EventEnvelope.scala`, `insertOutboxEvent`, and the `skunk-circe` dependency/import)
- [x] Task: Delete `StockEvents.scala`; reduce `AsyncApiChannels.channels` to an empty list (Green) `e084e71`
- [x] Task: Refactor, `sbt scalafmtCheck test` green `e084e71` (120/120 tests; deviation: the AsyncAPI validator rejects an empty document, so build-time generation is disabled via a `build.sbt` `asyncApiGenerate` override — the live raw-spec route still serves)
- [x] Task: Conductor - User Manual Verification 'Remove endpoint, store path and events' (Protocol in workflow.md) — `sbt scalafmtCheck test` green; old route returns 405 on the live cluster

## Phase 3: Registry artifacts and verification scripts

- [x] Task: Update `scripts/verify-schema-registry-check.sh` (correct the stale `inventory-service` group to `gluon`) and make it assert both stock-event artifacts are absent `e084e71`
- [x] Task: Delete the obsolete `verify-reserve-stock.sh`, `verify-kafka-publish.sh` and `verify-orderitemid-correlation.sh`; update `verify-kafka-asyncapi-docs.sh` to the empty channel list `e084e71`
- [x] Task: Run `sbt schemaRegistryCheck` against the local Apicurio, then DELETE `inventory-events-StockReserved`/`inventory-events-StockReservationFailed` from the `gluon` group; rerun the check and the script `e084e71` (both DELETEs returned 204; check is a clean no-op with an empty channel list; script green)
- [x] Task: Conductor - User Manual Verification 'Registry artifacts and scripts' (Protocol in workflow.md) — `scripts/verify-schema-registry-check.sh` green; both artifacts deleted (204) and absent from the `gluon` group

## Phase 4: Docs sweep + full suite

- [x] Task: inventory-service `README.md`, `conductor/product.md`, `conductor/tech-stack.md`: no stock-event publishing, per-item endpoint gone `e084e71`
- [x] Task: order-service `README.md`, `conductor/tech-stack.md`: drop the stale `StockEventConsumer`/`inventory-events` claims `4503898`
- [x] Task: `gluon/docs/system-design.md`: stock-event artifacts now deleted; `TECHNICAL_DEBT.md` TD-7.5 and `PLAN.md` Journey 1 row remain for the post-archive checkoff (system-design edited; checkoff pending the repo commit after archive)
- [x] Task: Full `sbt scalafmtCheck test`, coverage >80%; commit `e084e71` (120/120 tests)
- [x] Task: Conductor - User Manual Verification 'Docs + full suite' (Protocol in workflow.md) — 120/120 tests, scalafmt clean; order-service doc commit `4503898`

## Phase 5: Live verification

- [x] Task: Rebuild/redeploy inventory-service (`bin/k8s-local-up inventory-service`) and wait for rollout `91e40af`
- [x] Task: Add `scripts/verify-stock-events-retired.sh`: old endpoint gone; bulk reserve + idempotent release still work; Apicurio has neither artifact; `inventory-events` produces nothing `91e40af` (old route reports 405, not 404 — see Phase 1 note; script asserts 405)
- [x] Task: Run gshop's `scripts/verify-checkout-real.sh` against the redeployed cluster to prove the bulk path is intact end-to-end `91e40af` (201/402/409/400 all green)
- [x] Task: Conductor - User Manual Verification 'Live verification' (Protocol in workflow.md) — `scripts/verify-stock-events-retired.sh` and gshop's `scripts/verify-checkout-real.sh` both green against the redeployed cluster

## Risks / notes

- Deletion-heavy Scala change: the trait method removal breaks every test stub at once, so Phase 1 and 2 land together in one commit (compile-level Red, behavioral Red where expressible).
- `schemaRegistryCheck` with zero channels must genuinely no-op; if the plugin errors on an empty list, keep a single placeholder? No — fall back to disabling the task in build.sbt only if it cannot no-op, and record the deviation.
- Deleting registry artifacts is irreversible locally; the schemas are in git history (archived tracks) if they ever need reproducing.
- The Debezium connector and `outbox_events` table stay inert by decision; no data deleted.
