# Plan: Migrate to inventory-events topic with envelope

## Phase 1: Envelope + single-topic publisher [checkpoint: f6ea219]

- [x] Task: Write failing `StockEventPublisherSuite` tests against
  `inventory-events` (envelope decode, `eventType`/`source`/
  `correlationId` assertions, payload round-trip) (Red) `f6ea219`
- [x] Task: Add `EventEnvelope.scala`; update `StockEventPublisher` to
  publish both event types to `inventory-events` wrapped in the envelope
  (Green) `f6ea219`. Also fixed a real test-isolation bug this consolidation
  exposed: both tests now share one topic, so a fresh "earliest" consumer
  could see the other test's leftover message first — fixed by filtering
  on each test's own `correlationId` instead of just taking the first record.
- [x] Task: Run full `sbt test`, confirm green (88/88) `f6ea219`
- [x] Task: Update `scripts/verify-kafka-publish.sh` and
  `scripts/verify-orderitemid-correlation.sh` for the new topic/envelope
  shape; along the way found and fixed an unrelated pre-existing bug in
  `verify-kafka-publish.sh` (missing required `orderItemId` in its
  reservation requests — stale since the `stock-event-correlation_20261002`
  track made that field required) `abcd14f`
- [x] Task: Conductor - User Manual Verification 'Envelope + single-topic
  publisher' (Protocol in workflow.md) — both verify scripts run live
  end-to-end against a real `sbt run` instance + Testcontainers-free local
  Kafka (`docker compose`), all checks passed
