package inventoryservice

import munit.FunSuite
import purekafka.docs.ChannelDirection

class AsyncApiChannelsSuite extends FunSuite {

  test("AsyncApiChannels registers the OrderCancelled subscription (US-9.4)") {
    assertEquals(
      AsyncApiChannels.channels.map(c => c.topic -> c.eventType).toSet,
      Set("order-events" -> "OrderCancelled")
    )
    assertEquals(
      AsyncApiChannels.channels.map(_.direction),
      List(ChannelDirection.Subscribe: ChannelDirection)
    )
  }

  test(
    "the retired StockReserved/StockReservationFailed event types are not registered"
  ) {
    val eventTypes = AsyncApiChannels.channels.map(_.eventType).toSet
    for (retired <- List("StockReserved", "StockReservationFailed"))
      assert(
        !eventTypes.contains(retired),
        s"$retired should be retired (TD-7.5)"
      )
  }
}
