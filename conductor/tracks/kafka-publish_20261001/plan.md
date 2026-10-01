# Plan — kafka-publish_20261001

Plan approved at commit: 45f60af (`chore(conductor): archive track 'US-4.1:
reserve-stock endpoint'`) — every commit below is in addition to that baseline; diff
from there to review all track work as a whole.

## Phase 1: Tech stack & infra [checkpoint: pending]
- [x] Task: Update `tech-stack.md` documenting fs2-kafka + testcontainers-scala-kafka
      as new dependencies (workflow.md requires this before implementation)
      [7793b7f]
- [x] Task: Add `fs2-kafka`, `testcontainers-scala-kafka` to `build.sbt` [7793b7f]
- [x] Task: Add a `kafka` service (single-broker, e.g. `confluentinc/cp-kafka` or
      `apache/kafka` image) to `docker-compose.yml` for local dev — used
      `apache/kafka:3.8.0` in KRaft mode (no separate Zookeeper container), verified
      it starts healthy via `docker compose up -d kafka` [7793b7f]
- [x] Task: `sbt compile` confirms the new dependencies resolve cleanly [7793b7f]
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Tech stack & infra'
      (Protocol in workflow.md)

## Phase 2: Event payload + publisher
- [ ] Task: Write a failing integration test (Testcontainers Kafka) asserting a
      publish call produces exactly one JSON message with the correct fields on the
      right topic
- [ ] Task: Add `KafkaConfig` (bootstrap servers) to `InventoryServiceConfig`,
      following the existing `PostgresConfig` pattern
- [ ] Task: Define `StockReservedEvent(inventoryId, sku, quantity, timestamp)` /
      `StockReservationFailedEvent(inventoryId, sku, quantity, timestamp)` case
      classes + circe codecs (matching
      `gluon/docs/system-design.md`'s payload-contracts section)
- [ ] Task: Implement `StockEventPublisher` (fs2-kafka `KafkaProducer`-backed) with
      `publishReserved`/`publishFailed`, each wrapped in a short bounded timeout
      (e.g. 2s) that logs-and-swallows a failure/timeout rather than propagating it
- [ ] Task: Run tests, confirm green

## Phase 3: Wire into the HTTP endpoint
- [ ] Task: Write a failing test (fake/mock `StockEventPublisher`) asserting:
      success → `publishReserved` called once with correct fields; `InsufficientStock`
      → `publishFailed` called once; `InventoryNotFound`/`InvalidQuantity` → publisher
      never called
- [ ] Task: Wire `StockEventPublisher` into `reserveInventoryServerEndpoint` (call
      after `store.reserve`, before building the response) and into `Main` (construct
      the real fs2-kafka producer, pass it through)
- [ ] Task: Run tests, confirm green
- [ ] Task: Verify coverage (`sbt coverage test coverageReport`, target >80% on new
      code)
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Wire into the HTTP
      endpoint' (Protocol in workflow.md)
