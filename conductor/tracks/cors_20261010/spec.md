# Spec: Backport CORS support from pure-service-generator (TD-1.3)

## Overview
inventory-service wasn't independently browser-confirmed to lack CORS (gshop never calls it directly), but it's scaffolded from the same `pure-service-generator` template as catalog/cart/order-service, all three of which were confirmed affected and already fixed. Same proven pattern — a direct, no-new-design port. This track's own end-to-end verification (`scripts/verify-cors.sh`, a real curl check) doubles as the "confirm affected" step TD-1.3 calls for.

## Functional Requirements
1. Add `Cors.scala` (package `inventoryservice`) — identical to the proven `Cors.middleware[F[_]: Async]`: allow-all origins, GET/POST/PATCH/PUT/DELETE, `Content-Type` header, credentials disallowed.
2. Wire `Cors.middleware` into `Main.scala`, wrapping the fully-composed routes before `.withHttpApp(routes.orNotFound)`.
3. Add `CorsSuite.scala` — same 3 assertions, against inventory-service's own `/inventorys` route (bare GET/POST both exist; note the generator's known naive-pluralization limitation — `/inventorys`, not `/inventories`).

## Non-Functional Requirements
- No new dependency.
- No change to inventory-service's domain routes/logic.

## Acceptance Criteria
- [ ] `CorsSuite` passes (3 tests), 100% coverage on `Cors.scala`.
- [ ] Full `sbt test` suite green, no regressions.
- [ ] Real verification via `scripts/verify-cors.sh`: GET `/inventorys` with `Origin` shows ACAO; OPTIONS preflight shows correct Allow-Methods/-Headers — this also confirms the gap was real.

## Out of Scope
- The CORS policy itself (already decided at the generator level).
- payment-service, notification-service — separate tracks (remaining TD-1.3 items).
