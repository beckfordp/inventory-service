package inventoryservice

import munit.FunSuite
import purekafka.docs.AsyncApiChannel

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
}
