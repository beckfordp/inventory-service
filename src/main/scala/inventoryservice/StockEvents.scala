package inventoryservice

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.time.Instant

/** Payload shape pinned in `gluon/docs/system-design.md`'s "Payload contracts"
  * section - that cross-repo doc, not this case class, is the source of truth
  * order-service's future consumer (US-5.2) should read. Correlation is by
  * `sku`, not an inventory-record id: order-service (the consumer) never has
  * inventory-service's internal id to correlate against, only the sku it asked
  * to reserve.
  */
final case class StockReservedEvent(
    sku: String,
    quantity: Int,
    timestamp: Instant
)

object StockReservedEvent {
  implicit val codec: Codec[StockReservedEvent] = deriveCodec
}

final case class StockReservationFailedEvent(
    sku: String,
    quantity: Int,
    timestamp: Instant
)

object StockReservationFailedEvent {
  implicit val codec: Codec[StockReservationFailedEvent] = deriveCodec
}
