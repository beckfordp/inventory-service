# Spec — Adopt build-time schema registry check (Rework 5 step 3, ADR 0003)

## Overview
Wire inventory-service's existing `AsyncApiChannels` registrations into
`sbt-purekafka-docs`'s `schemaRegistryCheck` task (purerest `v0.3.0`/
`sbt-purekafka-docs 0.2.0-dev`, already really published to GitHub Packages
as part of order-service's equivalent track), so both of this service's
registered Kafka event schemas are checked/registered against Apicurio
Registry — locally against the real OrbStack instance, and in real CI via
an ephemeral container. Reuses the exact design settled in order-service's
`schema-registry_20261025` track (no new design questions): default
`schemaRegistryGroupId`, CI via a throwaway `docker run` Apicurio
container, no new purerest release needed.

## Functional Requirements
- Bump `project/plugins.sbt`'s `sbt-purekafka-docs` version `0.1.0-dev` ->
  `0.2.0-dev`.
- Bump `build.sbt`'s `purekafkaVersion` `0.2.1` -> `0.3.0` (the version
  `SchemaRegistryWriter` first shipped in).
- Add `schemaRegistryUrl` (local-dev default: the OrbStack cluster-DNS
  address, env-var overridable) / `schemaRegistryGroupId` (plugin default,
  `name.value` = `"inventory-service"`) settings to `build.sbt`.
- Add the same CI step shape as order-service's `ci.yml`: `docker run` a
  plain `apicurio/apicurio-registry:3.3.2` container, wait for
  `/health/ready` on port 9000, run `sbt schemaRegistryCheck` against it,
  tear the container down.
- New `scripts/verify-schema-registry-check.sh` (mirroring order-service's
  own script), registering both of this service's real channels.

## Non-Functional Requirements
- No change to runtime behavior — build/CI-time only.
- `inventory-service`'s CI currently lacks the `GH_PACKAGES_TOKEN` repo
  secret (confirmed, same pre-existing gap found in order-service's track)
  — needed regardless of this track's changes, since even the existing
  `scalafmtCheck + test` step can't resolve `purerestlib`/`purekafka`
  without it. The user adds this secret (same as order-service); not
  something I can do myself (blocked by the auto-mode classifier without
  explicit per-run approval).

## Acceptance Criteria
- `sbt schemaRegistryCheck` succeeds locally against the real OrbStack
  Apicurio instance, registering both channels (`StockReserved`,
  `StockReservationFailed`).
- The new CI step passes on a real GitHub Actions run, once
  `GH_PACKAGES_TOKEN` is in place.
- Full existing test suite + the new verify script run clean, no
  regressions.

## Out of Scope
- `payment-service`/`notification-service` adoption (their own, separate
  tracks).
- `pure-service-generator` template retrofit (Rework 5 step 4, separate
  track).
- Any change to the Debezium CDC / outbox publish path.
