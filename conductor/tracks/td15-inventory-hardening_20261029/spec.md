# TD-15.4 — Cross-repo consistency hardening (inventory-service)

## Overview

Inventory-service slice of gluon TD-15: typed inventory ids at the boundary,
parse-don't-validate for reservation ids, `asyncApiHtmlDir` config, ScalaCheck,
and narrower effect bounds.

## Functional Requirements

1. **Typed ids** — opaque `InventoryId` parsed once at the route boundary;
   store get/update/delete take `InventoryId`; malformed → 400. Replace the
   remaining `Try(UUID.fromString)` uses with `Either.catchOnly`.
2. **Config** — `asyncApiHtmlDir` with `${?ASYNCAPI_HTML_DIR}`; no `sys.env`.
3. **Tests** — `munit-scalacheck` + id/reservation properties.
4. **Effect constraints** — `Cors` → `Monad`; `HealthRoutes` health →
   `Applicative`, ready/routes → `Monad`; route handlers narrowed where
   possible.

## Acceptance Criteria

- Malformed inventory id → 400; no raw UUID parsing in stores.
- Full suite + scalafmt green; coverage >80%.

## Out of Scope

- No Kafka contract change (inventory publishes nothing); other services.
