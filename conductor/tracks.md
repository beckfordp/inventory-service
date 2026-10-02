# Project Tracks

This file tracks all major tracks for the project.

---

## Backlog

Title-only placeholders for future tracks — not yet detailed (no spec/plan, no linked
folder), so `/conductor:implement` cannot pick these up by accident. Reorder freely as
priorities change. When ready to work on one, run `/conductor:newTrack <title>` to go
through the spec/plan questions and promote it into a real track above.

- Accept and echo `orderItemId` on `POST /inventorys/reservations` and on
  both `inventory.stock-reserved`/`inventory.stock-reservation-failed`
  events (gluon/docs/system-design.md updated 2026-10-02 — correlation
  switches from `sku` to `orderItemId`, an opaque string inventory-service
  just stores and echoes, no order-domain coupling). Confirmed via code
  read on 2026-10-02: not yet implemented on either side — order-service's
  `InventoryClient.reserve` still only sends `{sku, quantity}` too, so this
  is a paired change with that repo, not inventory-service-only.

---
