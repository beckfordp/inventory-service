# Spec: Journey telemetry (gluon PERF-4.1–4.3, 4.6, 4.11)

## Overview
gluon PLAN.md Performance 4 for inventory-service: adopt purerestlib/purekafka 0.8.0 and
count reservation outcomes for the Christmas Rush dashboard (Q10).

## Functional Requirements
1. Bump purerestlib + purekafka to 0.8.0; `Main` passes `runtime.metrics` (PERF-4.1).
2. `PoolMetrics` on the pool `inventory` (PERF-4.3).
3. `EventAge` in both consumers (fulfilment-events, order-events) (PERF-4.2).
4. `InventoryJourneyMetrics` store decorator (PERF-4.6):
   `inventory.reservation.outcome{reserved|insufficient|already_released}` per
   `reserveOrder`; `inventory.reservation.lines{outcome=released|shipped}` counting
   lines released by `releaseOrder` and shipped by `markShipped`.
   `Milestones` lines `order_reserved`, `order_released`, `order_shipped` (PERF-4.11).

## Non-Functional Requirements
- No order ids on labels. Coverage > 80% on new code. scalafmt clean.

## Acceptance Criteria
- Decorator unit tests on the in-memory store. Live: metrics port shows the new series.

## Out of Scope
- Consumer spans (Perf 5). No outbox (inventory has none since V5).
