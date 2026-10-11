# Overview
Migrate inventory-service's Kafka publishing from two per-event-type topics
(`inventory.stock-reserved`, `inventory.stock-reservation-failed`) to one
`inventory-events` topic, with every event wrapped in a shared envelope
carrying `eventType` so consumers dispatch on that instead of topic
subscription. Part of a coordinated cross-repo cutover (gshop's `gluon`
repo, `PLAN.md`'s Rework 2; [ADR 0009](../../../../docs/adr/0009-one-kafka-topic-per-domain.md),
Accepted 2026-10-11) — order-service's `StockEventConsumer` changes in
lockstep in its own repo/track.

# Functional Requirements
- New `EventEnvelope` case class: `eventId: String`, `eventType: String`,
  `timestamp: Instant`, `source: String`, `correlationId: String`,
  `payload: Json`. `source` is always `"inventory-service"`. `eventId` is a
  fresh `UUID.randomUUID()` per publish. `correlationId` is the event's
  `orderItemId` — inventory-service doesn't know the owning order's id
  (deliberate decoupling, see `StockEvents.scala`'s existing doc comment),
  so this isn't a unified cross-service trace id for the same checkout;
  accepted as a known gap, not fixed here (see Out of Scope).
- `StockEventPublisher.publishReserved`/`publishFailed` both produce to a
  single `inventory-events` topic (replacing `reservedTopic`/
  `reservationFailedTopic`), wrapping the existing payload with
  `eventType = "StockReserved"` / `"StockReservationFailed"` respectively.
- Partition key unchanged — still `event.sku`.

# Non-Functional Requirements
- No new dependency — `io.circe.Json` already available via existing circe
  deps.
- No change to `publishTimeout`, the `noOp` stub, or any call site in
  `InventoryRoutes.scala` beyond what the topic/payload-shape change
  forces.

# Acceptance Criteria
- [x] `StockEventPublisherSuite` (real Testcontainers Kafka) confirms both
  `publishReserved`/`publishFailed` land on `inventory-events`, decode as
  a valid `EventEnvelope`, with the right `eventType`/`source`/
  `correlationId`, and `payload` decodes to the original event shape.
- [x] Full `sbt test` suite stays green (88/88).

# Out of Scope
- Passing `orderId` through `POST /inventorys/reservations` to unify
  `correlationId` with order-service's/payment-service's — a real gap,
  tracked separately (see backlog), not part of this envelope migration.
- Any change to `POST /inventorys/reservations`'s synchronous HTTP
  contract.
- `purekafka`-based consumer resilience (Rework 3) — unrelated, separate
  track.
