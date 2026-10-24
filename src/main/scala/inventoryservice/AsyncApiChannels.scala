package inventoryservice

import purekafka.docs.{
  AsyncApiChannel,
  AsyncApiChannelsProvider,
  ChannelDirection,
  PublisherKind
}

/** Registers inventory-service's Kafka event contracts for AsyncAPI
  * documentation (ADR 0010's AsyncAPI follow-up). inventory-service never calls
  * a Kafka producer directly — `StockReserved`/`StockReservationFailed` are
  * published via its own `outbox_events` table and the Debezium CDC connector's
  * `route.topic.replacement` config
  * (`infra/k8s/local-infra/debezium-connectors-register.yaml`, gluon repo),
  * same as order-service's own outbox-published events — hence `Publish(Cdc)`,
  * not a direct producer.
  *
  * Implements `AsyncApiChannelsProvider` (not just a plain `List`) so the
  * sbt-purekafka-docs plugin's `AsyncApiSpecWriter` can load this singleton
  * reflectively by class name, configured via `build.sbt`'s
  * `asyncApiChannelsClass`.
  */
object AsyncApiChannels extends AsyncApiChannelsProvider {

  // Not read anywhere else in this app's own code — the outbox pattern means
  // inventory-service never names a Kafka topic directly, only Debezium's
  // connector config does (see this object's own doc comment).
  private val inventoryEventsTopic = "inventory-events"

  val channels: List[AsyncApiChannel[?]] = List(
    AsyncApiChannel[StockReservedEvent](
      topic = inventoryEventsTopic,
      eventType = "StockReserved",
      direction = ChannelDirection.Publish(PublisherKind.Cdc)
    ),
    AsyncApiChannel[StockReservationFailedEvent](
      topic = inventoryEventsTopic,
      eventType = "StockReservationFailed",
      direction = ChannelDirection.Publish(PublisherKind.Cdc)
    )
  )
}
