# Product Guide — inventory-service

## Context
Part of the **Gluon** platform (v1) — a production-grade microservices
e-commerce platform built pure-FP-first in Scala 3 (Cats Effect / http4s).
See the cross-repo [Gluon Product Vision](../../../docs/product.md) (in the
`gluon/` monorepo root) for the full platform vision, naming scheme, and
goals. This document scopes that vision down to inventory-service's own
slice.

## What this service does
inventory-service owns the **Inventory** domain entity — stock levels per
SKU — and backs the synchronous stock-reservation call order-service makes
during checkout, so orders don't oversell. It is the only service permitted
to read or write the `inventory` Postgres database (one-db-per-service).

Generated via `pure-service-generator` (giter8 template over `purerest`),
field-spec applied from `gluon/specs/inventory.yaml`, then hand-extended per
`gluon/backlogs/inventory-service.md`.

## Domain model
- **Inventory** (generated) — `sku` (create-only), `quantityAvailable`
  (Int), `quantityReserved` (Int; server-defaulted to `0` at creation).
  Flag: "server-defaulted" only constrains the *create* endpoint — codegen
  v1 has no way to make a field server-set-once-then-read-only, so
  `quantityReserved` is still a plain client-writable field on `PATCH`/`PUT`
  (`UpdateInventoryRequest` takes both `quantityAvailable` and
  `quantityReserved`), same as `quantityAvailable`. Worth hardening later
  (same class of gap as order-service's `status`-as-String flag).
- The generated endpoints are plain CRUD (create/get/update/delete via
  `/inventorys`). They are **not** yet the dedicated "reserve stock"
  operation US-4.1 calls for — an atomic
  decrement-`quantityAvailable`/increment-`quantityReserved` check that can
  fail (insufficient stock) and gets called synchronously by order-service.
  That's still backlog work, not something codegen produced.

## User stories in scope (gluon/docs/user-stories.md)
- US-4.1 — reserve-stock endpoint (sync, called by order-service)
- US-5.1 — publish `inventory.stock-reserved` / `inventory.stock-reservation-failed`

## Sequencing (gluon/PLAN.md)
- **Phase 1** (parallel with order-service, no dependency between them) —
  US-4.1
- **Phase 3** (parallel with order-service once each side is independently
  testable) — US-5.1, publish side tested against an embedded/test Kafka,
  not that order-service reacts to it

## Events
- Publishes: `inventory.stock-reserved`, `inventory.stock-reservation-failed`
- Consumes: none

## Out of scope for this service
- Order/customer data (order-service's job; inventory-service never reads
  or writes the `order` database)
- Catalog data (catalog-service's job — SKU is a logical reference only,
  never looked up live)
- Payment processing (payment-service's job)
