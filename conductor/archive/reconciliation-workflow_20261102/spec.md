# Specification: ADM-4 — reconciliation workflow

## Overview

inventory-service records first-wins `cancel_after_dispatch` /
`dispatch_after_release` flags with no API or resolve action. This track adds
the open/resolved queue and operator resolve, per ADR 0026 and the contract
in `gluon/docs/system-design.md`.

## Functional Requirements

- `V7__extend_inventory_reconciliation.sql`: status/operator/note/resolved_at
  columns, named status + resolution checks, `(status, created_at desc)` index.
- `domain/Reconciliation.scala` (orderId `UUID`): status ADT/parse, row, pure
  resolve rule.
- `InventoryStore`: list newest first; `resolveReconciliation` conditional
  update in one transaction + same-session read → Resolved/AlreadyResolved/
  NotFound; first resolver wins. Insert-only OTel counter
  `reconciliation.flags.recorded`.
- `ReconciliationRoutes.scala`: `GET /reconciliations` (filters, 400 unknown
  status, empty on malformed orderId) and `PATCH /reconciliations/{orderId}`
  (UUID path, dev token required → 401, only `resolved` → 400, 404 unknown,
  idempotent 200).
- Copy `OperatorAuth`; `operator-auth` config (`OPERATOR_TOKEN`/
  `OPERATOR_ID`) + startup warning; add `Authorization` to CORS; register
  routes in `AppRoutes`.
- Update stubs, `InventoryDocsSuite`, `MigrationsSuite`, config suite and
  `scripts/verify-rest-docs.sh`.

## Non-Functional
- TDD; `sbt scalafmtCheck test` green; >80% coverage on new code; no
  Kafka/registry changes.

## Out of Scope
- gshop UI (ADM-7); live demo (ADM-9); reopening resolved flags.
