-- Transactional outbox (ADR 0008, TD-3.3). aggregate_id holds orderItemId
-- (not sku) to match system-design.md's documented inventory-events
-- partition key -- correcting a pre-existing drift in StockEventPublisher,
-- which keyed Kafka records by sku. No sent/processed column: cleanup is
-- time-based (TD-3.1's CronJob), not consumption-tracked.
CREATE TABLE "outbox_events" (
    id UUID PRIMARY KEY,
    aggregate_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    trace_parent TEXT,
    correlation_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
