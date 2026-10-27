package inventoryservice

import munit.FunSuite

class AsyncApiChannelsSuite extends FunSuite {

  test(
    "AsyncApiChannels.channels is empty after retiring the stock events (TD-7.5)"
  ) {
    assertEquals(AsyncApiChannels.channels, Nil)
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
