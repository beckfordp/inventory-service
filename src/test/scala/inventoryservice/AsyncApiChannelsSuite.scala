package inventoryservice

import munit.FunSuite
import purekafka.docs.ChannelDirection

class AsyncApiChannelsSuite extends FunSuite {

  test(
    "AsyncApiChannels registers the OrderCancelled and DispatchConfirmed subscriptions"
  ) {
    assertEquals(
      AsyncApiChannels.channels.map(c => c.topic -> c.eventType).toSet,
      Set(
        "order-events" -> "OrderCancelled",
        "fulfilment-events" -> "DispatchConfirmed"
      )
    )
    assertEquals(
      AsyncApiChannels.channels.map(_.direction),
      List(ChannelDirection.Subscribe, ChannelDirection.Subscribe)
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
