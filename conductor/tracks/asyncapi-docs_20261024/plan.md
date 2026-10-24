# Plan — AsyncAPI docs for inventory-events (ADR 0010)

## Phase 1: Dependency bump + schema derivation [checkpoint: b2a073b]
- [x] Task: Write failing test asserting `AsyncApiChannel[StockReservedEvent]`/
      `AsyncApiChannel[StockReservationFailedEvent]` construct (needs an
      implicit tapir `Schema[A]`) (Red) `c7cb0aa`
- [x] Task: Bump `io.github.beckfordp:purekafka` to the pinned version
      order-service uses (includes the AsyncAPI topic-merge fix); add tapir
      `Schema.derived` to both event case classes in `StockEvents.scala`
      (Green) `c7cb0aa` — committed Red+Green together
- [x] Task: Conductor - User Manual Verification 'Phase 1: Dependency bump +
      schema derivation' (Protocol in workflow.md) `b2a073b` — reduced-prompting
      mode: automated-test verification only (87 passed), no interactive
      walkthrough; see git note on `b2a073b`

## Phase 2: Channel registration + raw-spec route [checkpoint: ef306ee]
- [x] Task: Write failing test: a new `AsyncApiChannels` registry exposes
      both channels on topic `inventory-events`, correct eventType,
      `ChannelDirection.Publish(PublisherKind.Cdc)` (Red) `a9007f2`
- [x] Task: Implement `AsyncApiChannels.scala` (Green) `a9007f2`
- [x] Task: Write failing test: `GET /docs/asyncapi.json` returns 200 with a
      document containing both event types (Red) `a9007f2` — deviation:
      already-Green, not Red (building `Docs.routes` directly in-test needs
      no `Main.scala` wiring), same deviation order-service's own track
      noted
- [x] Task: Mount `purekafka.docs.Docs.routes[F]` in `Main.scala`, merged
      with existing OpenAPI routes (Green) `a9007f2` — applied
      order-service's own route-ordering lesson from the start
      (`asyncApiRoutes <+> docsRoutes`, not the reverse)
- [x] Task: Conductor - User Manual Verification 'Phase 2: Channel
      registration + raw-spec route' (Protocol in workflow.md) `ef306ee` —
      reduced-prompting mode: automated-test verification only (89 passed),
      no interactive walkthrough; see git note on `ef306ee`

## Phase 3: sbt plugin wiring + static HTML serving [checkpoint: c452746]
- [x] Task: `project/plugins.sbt` — add `sbt-purekafka-docs` + GitHub
      Packages resolver/credentials (copy order-service's verbatim)
      `6eeddee`
- [x] Task: `build.sbt` — enable `PureKafkaDocsPlugin`, set
      `asyncApiChannelsClass`/`asyncApiTitle`/`asyncApiVersion` `6eeddee`
- [x] Task: Adapt `verify-kafka-asyncapi-docs.sh` from order-service (own
      port/route; no `generate-kafka-asyncapi-docs.sh` to adapt - superseded
      by the sbt plugin in order-service too) `6eeddee`
- [x] Task: Add `/docs/asyncapi` route in `Main.scala` serving the generated
      static HTML, sibling to the raw-spec route `6eeddee`
- [x] Task: Conductor - User Manual Verification 'Phase 3: sbt plugin
      wiring + static HTML serving' (Protocol in workflow.md) `c452746` —
      reduced-prompting mode: end-to-end script verification only
      (`./scripts/verify-kafka-asyncapi-docs.sh`, all checks passed); see
      git note on `c452746`

## Phase 4: Docs + full verification [checkpoint: f6ec3ee]
- [x] Task: Update README with a "View Kafka AsyncAPI docs" section
      `f71a960`
- [x] Task: Run full test suite + `./scripts/verify-kafka-asyncapi-docs.sh`
      end-to-end; confirm no regressions — 89 tests passing, scalafmtCheck
      clean, verify script passed end-to-end in Phase 3; no code changes,
      nothing further to commit for this task
- [x] Task: Conductor - User Manual Verification 'Phase 4: Docs + full
      verification' (Protocol in workflow.md) `f6ec3ee` —
      reduced-prompting mode: automated/end-to-end verification only (89
      passed, verify script passed in Phase 3), no interactive walkthrough;
      see git note on `f6ec3ee`

## Phase: Review Fixes
- [x] Task: Apply review suggestions `054ff71`
