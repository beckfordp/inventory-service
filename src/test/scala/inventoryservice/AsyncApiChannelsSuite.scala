package inventoryservice

import munit.FunSuite
import purekafka.docs.{AsyncApiChannel, ChannelDirection, PublisherKind}

class AsyncApiChannelsSuite extends FunSuite {

  test(
    "AsyncApiChannel constructs for both of inventory-service's registered event types"
  ) {
    val stockReserved =
      AsyncApiChannel[StockReservedEvent](
        topic = "inventory-events",
        eventType = "StockReserved"
      )
    val stockReservationFailed =
      AsyncApiChannel[StockReservationFailedEvent](
        topic = "inventory-events",
        eventType = "StockReservationFailed"
      )

    assertEquals(stockReserved.topic, "inventory-events")
    assertEquals(stockReservationFailed.topic, "inventory-events")
  }

  test(
    "AsyncApiChannels.channels registers both event types on inventory-events, as Publish(Cdc)"
  ) {
    val topicsAndEventTypes =
      AsyncApiChannels.channels.map(c => c.topic -> c.eventType).toSet

    assertEquals(
      topicsAndEventTypes,
      Set(
        "inventory-events" -> "StockReserved",
        "inventory-events" -> "StockReservationFailed"
      )
    )

    for (channel <- AsyncApiChannels.channels)
      assertEquals(
        channel.direction,
        ChannelDirection.Publish(PublisherKind.Cdc): ChannelDirection,
        s"expected ${channel.eventType} to be Publish(Cdc) - inventory-service's own outbox publishes it via Debezium CDC"
      )
  }
}
