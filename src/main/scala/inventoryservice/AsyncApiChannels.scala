package inventoryservice

import purekafka.docs.{AsyncApiChannel, AsyncApiChannelsProvider}

/** inventory-service's Kafka contracts for AsyncAPI documentation (ADR 0010).
  *
  * TD-7.5 retired the only two, `StockReserved`/`StockReservationFailed`:
  * reservation is synchronous and all-or-nothing, so this service publishes no
  * events and consumes none. The provider is kept (with an empty list) so
  * `/docs/asyncapi.json`, the sbt-generated static HTML and
  * `schemaRegistryCheck` stay wired for whenever a future inventory event lands
  * — the registry check is a no-op until then.
  */
object AsyncApiChannels extends AsyncApiChannelsProvider {
  val channels: List[AsyncApiChannel[?]] = Nil
}
