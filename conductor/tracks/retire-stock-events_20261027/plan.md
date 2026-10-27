# Plan: TD-7.5 — retire per-item reservations and stock events

## Phase 1: Tests to the target API

- [ ] Task: Replace the five `POST /inventorys/reservations` route tests with one asserting the path is 404, and keep the bulk reserve/release suites unchanged (Red)
- [ ] Task: Remove `reserve` from the in-memory/Postgres store suites and their test stubs; rewrite the "never collectively oversell" test against `reserveOrder` with five distinct order ids (Red / compile-level)
- [ ] Task: Update `AsyncApiChannelsSuite`, `InventoryAsyncApiDocsSuite` and `InventoryDocsSuite` to assert the retired event types and per-item path are absent (Red)
- [ ] Task: Conductor - User Manual Verification 'Tests to the target API' (Protocol in workflow.md)

## Phase 2: Remove the endpoint, store path and events

- [ ] Task: Delete `ReserveInventoryRequest`, its tapir endpoint/error output, `reserveInventoryServerEndpoint`, the `routes`/`AppRoutes` entries (Green)
- [ ] Task: Delete `InventoryStore.reserve` (trait, in-memory, Postgres), the `reserveInventory`/`selectIdBySku` queries, and dead outbox helpers left without a writer (Green)
- [ ] Task: Delete `StockEvents.scala`; reduce `AsyncApiChannels.channels` to an empty list (Green)
- [ ] Task: Refactor, `sbt scalafmtCheck test` green
- [ ] Task: Conductor - User Manual Verification 'Remove endpoint, store path and events' (Protocol in workflow.md)

## Phase 3: Registry artifacts and verification scripts

- [ ] Task: Update `scripts/verify-schema-registry-check.sh` (correct the stale `inventory-service` group to `gluon`) and make it assert both stock-event artifacts are absent
- [ ] Task: Delete the obsolete `verify-reserve-stock.sh`, `verify-kafka-publish.sh` and `verify-orderitemid-correlation.sh`; update `verify-kafka-asyncapi-docs.sh` to the empty channel list
- [ ] Task: Run `sbt schemaRegistryCheck` against the local Apicurio, then DELETE `inventory-events-StockReserved`/`inventory-events-StockReservationFailed` from the `gluon` group; rerun the check and the script
- [ ] Task: Conductor - User Manual Verification 'Registry artifacts and scripts' (Protocol in workflow.md)

## Phase 4: Docs sweep + full suite

- [ ] Task: inventory-service `README.md`, `conductor/product.md`, `conductor/tech-stack.md`: no stock-event publishing, per-item endpoint gone
- [ ] Task: order-service `README.md`, `conductor/tech-stack.md`: drop the stale `StockEventConsumer`/`inventory-events` claims
- [ ] Task: `gluon/docs/system-design.md`: stock-event artifacts now deleted; `TECHNICAL_DEBT.md` TD-7.5 and `PLAN.md` Journey 1 row remain for the post-archive checkoff
- [ ] Task: Full `sbt scalafmtCheck test`, coverage >80%; commit
- [ ] Task: Conductor - User Manual Verification 'Docs + full suite' (Protocol in workflow.md)

## Phase 5: Live verification

- [ ] Task: Rebuild/redeploy inventory-service (`bin/k8s-local-up inventory-service`) and wait for rollout
- [ ] Task: Add `scripts/verify-stock-events-retired.sh`: old endpoint 404; bulk reserve + idempotent release still work; Apicurio has neither artifact; `inventory-events` produces nothing
- [ ] Task: Run gshop's `scripts/verify-checkout-real.sh` against the redeployed cluster to prove the bulk path is intact end-to-end
- [ ] Task: Conductor - User Manual Verification 'Live verification' (Protocol in workflow.md)

## Risks / notes

- Deletion-heavy Scala change: the trait method removal breaks every test stub at once, so Phase 1 and 2 land together in one commit (compile-level Red, behavioral Red where expressible).
- `schemaRegistryCheck` with zero channels must genuinely no-op; if the plugin errors on an empty list, keep a single placeholder? No — fall back to disabling the task in build.sbt only if it cannot no-op, and record the deviation.
- Deleting registry artifacts is irreversible locally; the schemas are in git history (archived tracks) if they ever need reproducing.
- The Debezium connector and `outbox_events` table stay inert by decision; no data deleted.
