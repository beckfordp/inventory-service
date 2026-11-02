# Plan: US-9.4 — release reservations on `OrderCancelled`

## Phase 1: Kafka wiring

- [x] Task: Add fs2-kafka, `KafkaConfig`, application.conf block, Main wiring
- [x] Task: `InventoryServiceConfigSuite` fixture covers kafka
- [x] Task: Conductor - User Manual Verification 'Kafka wiring' (Protocol in workflow.md)

## Phase 2: Consumer

- [x] Task: Failing handler tests — release, duplicate no-op, malformed DLQ, unrelated type ignored (Red)
- [x] Task: `OrderCancelledConsumer` + `KafkaConsumers` DLQ/supervisor (Green)
- [x] Task: AsyncAPI channel + channel suite; remove `asyncApiGenerate` empty-dir override
- [x] Task: Conductor - User Manual Verification 'Consumer' (Protocol in workflow.md)

## Phase 3: Schema check

- [x] Task: Add `order-events-OrderCancelled` to CONSUMES; `sbt schemaRegistryCheck`
- [x] Task: Conductor - User Manual Verification 'Schema check' (Protocol in workflow.md)
