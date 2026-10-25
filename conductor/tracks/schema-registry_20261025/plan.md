# Plan — Adopt build-time schema registry check (Rework 5 step 3, ADR 0003)

## Phase 1: Local sbt wiring + verification against the real OrbStack Apicurio instance
- [ ] Task: Bump `project/plugins.sbt`'s `sbt-purekafka-docs` `0.1.0-dev`
      -> `0.2.0-dev`; bump `build.sbt`'s `purekafkaVersion` `0.2.1` ->
      `0.3.0`
- [ ] Task: Add `schemaRegistryUrl`/`schemaRegistryGroupId` settings to
      `build.sbt` (same env-var-override shape as order-service's)
- [ ] Task: New `scripts/verify-schema-registry-check.sh` (mirroring
      order-service's own script), run against the real local Apicurio
      instance: registers both real channels + sets the `BACKWARD` rule
      on each on first run; a second identical run is a no-op
- [ ] Task: Run full test suite + the verify script end-to-end; confirm
      no regressions
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Local sbt
      wiring + verification' (Protocol in workflow.md)

## Phase 2: Real CI wiring
- [ ] Task: Add the same `docker run` Apicurio CI step shape as
      order-service's `ci.yml` to this repo's `ci.yml`
- [ ] Task: Confirm/arrange the `GH_PACKAGES_TOKEN` repo secret is in
      place (pre-existing gap, same as order-service — flag to the user
      if still missing)
- [ ] Task: Push this branch and confirm the new CI step passes on a
      real GitHub Actions run
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Real CI
      wiring' (Protocol in workflow.md)
