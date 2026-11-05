# Spec: business spans (gluon PERF-5.9)

## Overview
Traces show the HTTP and `<topic> process` spans but nothing inside them. Name
the business steps so a trace reads as the business flow.

## Functional Requirements
1. `InventoryStoreTracing` decorator: spans `inventory.reserve|release|mark_shipped` with `order.id`, `outcome`.


## Acceptance Criteria
- Tests (purerest `Tracing.test`): each traced step finishes one span with the
  expected name and attributes, parented on the caller's span; a raised error
  sets span status Error.
- `sbt scalafmtCheck test` green.
- Live: the spans appear in Tempo under the request/consumer span.

## Out of Scope
- skunk per-query spans; reads, reconciliation; HTTP client propagation (PERF-5.10).
