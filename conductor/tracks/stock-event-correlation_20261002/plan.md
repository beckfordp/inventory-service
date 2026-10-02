# Implementation Plan: add orderItemId correlation id to stock-reservation REST/Kafka contract

## Phase 1: Add orderItemId end-to-end (request -> events) [checkpoint: e2a467e]

- [x] Task: Add `orderItemId: String` to `ReserveInventoryRequest`, `StockReservedEvent`, `StockReservationFailedEvent`; thread it from the request through `publishOutcome` into both event constructors in `InventoryRoutes` `e654de5`
- [x] Task: Update all existing call sites forced by the new required field (8 in `InventoryRoutesSuite`, 2 in `StockEventPublisherSuite`); extend `InventoryRoutesSuite`/`StockEventPublisherSuite` with new assertions - a successful reservation's published `stock-reserved` event carries the request's `orderItemId`, a failed reservation's `stock-reservation-failed` event does too, and a request missing `orderItemId` returns 400 `d9ef28e`
- [x] Task: Conductor - User Manual Verification 'add orderItemId correlation id to stock-reservation REST/Kafka contract' (final, Protocol in workflow.md) `e2a467e`

Single phase/commit: adding a required field to an existing case class
forces every call site to update in lockstep (same Scala
whole-module-compile constraint hit on prior order-service tracks) - can't
cleanly split into separate Red/Green commits here.
