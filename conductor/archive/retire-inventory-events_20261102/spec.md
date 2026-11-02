# Specification: TD-22 — retire inventory-events producer plumbing

## Overview

inventory-service publishes nothing. Its `outbox_events` table, Debezium
connector, cleanup CronJob and `inventory-events` topic are inert. Remove
them; keep the `OrderCancelled` consumer and its DLQ.

## Functional Requirements

- Drop the unused `outbox_events` table with `V5__drop_outbox_events.sql`
  (latest is V4).
- Remove the inventory-specific migrations-suite expectations, README and
  product/tech-stack live references to outbox/stock events.
- Replace `verify-stock-events-retired.sh` with a check that the connector is
  404, the topic and table are absent, and retired registry/AsyncAPI names
  stay absent.
- Infra: remove the connector config and cleanup CronJob; the register Job
  deletes the retired connector (404 tolerated); local-up deletes the
  CronJob/topic.
- Acceptance precheck drops the inventory connector; docs/diagrams/ADR 0008
  update.

## Acceptance Criteria

- Fresh and existing local clusters have no inventory connector/topic/
  CronJob/outbox table.
- inventory-service tests and acceptance checks pass.
- No live inventory-events reference remains; historical records stay.
