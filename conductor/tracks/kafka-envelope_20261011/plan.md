# Plan: Migrate to inventory-events topic with envelope

## Phase 1: Envelope + single-topic publisher

- [ ] Task: Write failing `StockEventPublisherSuite` tests against
  `inventory-events` (envelope decode, `eventType`/`source`/
  `correlationId` assertions, payload round-trip) (Red)
- [ ] Task: Add `EventEnvelope.scala`; update `StockEventPublisher` to
  publish both event types to `inventory-events` wrapped in the envelope
  (Green)
- [ ] Task: Run full `sbt test`, confirm green
- [ ] Task: Conductor - User Manual Verification 'Envelope + single-topic
  publisher' (Protocol in workflow.md)
