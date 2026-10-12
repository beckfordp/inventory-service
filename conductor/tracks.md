# Project Tracks

This file tracks all major tracks for the project.

---

## Backlog

Title-only placeholders for future tracks — not yet detailed (no spec/plan, no linked
folder), so `/conductor:implement` cannot pick these up by accident. Reorder freely as
priorities change. When ready to work on one, run `/conductor:newTrack <title>` to go
through the spec/plan questions and promote it into a real track above.

- TD-3.3 / ADR 0008: transactional outbox — add `outbox_events` table (Flyway migration), write to it in the same transaction as the stock-reservation state change, drop the direct `StockEventPublisher` calls

---
