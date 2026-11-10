// SPDX-License-Identifier: MIT

package inventoryservice

import purekafka.docs.{
  AsyncApiChannel,
  AsyncApiChannelsProvider,
  ChannelDirection
}

/** inventory-service's Kafka contracts for AsyncAPI documentation (ADR 0010).
  *
  * TD-7.5 retired `StockReserved`/`StockReservationFailed`; inventory still
  * publishes nothing. US-9.4 adds its first subscription:
  * `order-events: OrderCancelled`, which releases the order's reservation.
  */
object AsyncApiChannels extends AsyncApiChannelsProvider {
  val channels: List[AsyncApiChannel[?]] = List(
    AsyncApiChannel[OrderCancelledEvent](
      topic = OrderCancelledConsumer.topic,
      eventType = "OrderCancelled",
      direction = ChannelDirection.Subscribe
    ),
    AsyncApiChannel[DispatchConfirmedEvent](
      topic = FulfilmentEventConsumer.topic,
      eventType = "DispatchConfirmed",
      direction = ChannelDirection.Subscribe
    )
  )
}
