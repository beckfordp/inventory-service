# Plan — kafka-publish_20261001

Plan approved at commit: 45f60af (`chore(conductor): archive track 'US-4.1:
reserve-stock endpoint'`) — every commit below is in addition to that baseline; diff
from there to review all track work as a whole.

## Phase 1: Tech stack & infra [checkpoint: bcc5b9c]
- [x] Task: Update `tech-stack.md` documenting fs2-kafka + testcontainers-scala-kafka
      as new dependencies (workflow.md requires this before implementation)
      [7793b7f]
- [x] Task: Add `fs2-kafka`, `testcontainers-scala-kafka` to `build.sbt` [7793b7f]
- [x] Task: Add a `kafka` service (single-broker, e.g. `confluentinc/cp-kafka` or
      `apache/kafka` image) to `docker-compose.yml` for local dev — used
      `apache/kafka:3.8.0` in KRaft mode (no separate Zookeeper container), verified
      it starts healthy via `docker compose up -d kafka` [7793b7f]
- [x] Task: `sbt compile` confirms the new dependencies resolve cleanly [7793b7f]
- [x] Task: Conductor - User Manual Verification 'Phase 1: Tech stack & infra'
      (Protocol in workflow.md) — `docker compose up -d` brings up postgres+kafka
      together, both healthy, clean teardown [bcc5b9c]

## Phase 2: Event payload + publisher [checkpoint: 7996d69]
- [x] Task: Write a failing integration test (Testcontainers Kafka) asserting a
      publish call produces exactly one JSON message with the correct fields on the
      right topic [6cbdfb7]
- [x] Task: Add `KafkaConfig` (bootstrap servers) to `InventoryServiceConfig`,
      following the existing `PostgresConfig` pattern [6cbdfb7]
- [x] Task: Define `StockReservedEvent(inventoryId, sku, quantity, timestamp)` /
      `StockReservationFailedEvent(inventoryId, sku, quantity, timestamp)` case
      classes + circe codecs (matching
      `gluon/docs/system-design.md`'s payload-contracts section) [6cbdfb7].
      **Correction made during Phase 3:** dropped `inventoryId` from both events
      (and from the cross-repo contract doc) — `InventoryStore.reserve`'s failure
      case carries no `Inventory` to pull an id from, and order-service (the
      consumer) never has inventory-service's internal id to correlate against
      in the first place, only `sku`. Final shape:
      `StockReservedEvent(sku, quantity, timestamp)` /
      `StockReservationFailedEvent(sku, quantity, timestamp)`. [486683f]
- [x] Task: Implement `StockEventPublisher` (fs2-kafka `KafkaProducer`-backed) with
      `publishReserved`/`publishFailed`, each wrapped in a short bounded timeout
      (2s). **Deviation from the task text as originally written:** the publisher
      itself does NOT log-and-swallow a failure — it propagates a real
      failure/timeout, matching how `InventoryStore` stays a pure capability with
      no logging inside it in this codebase. Phase 3's HTTP wiring is responsible
      for `.attempt` + logging + discarding the result, the same separation the
      existing routes already use for `InventoryStore` errors. [6cbdfb7]
- [x] Task: Run tests, confirm green — 69 passed, 0 failed [6cbdfb7]

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
