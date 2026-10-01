package inventoryservice

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.time.Instant

/** Payload shape pinned in `gluon/docs/system-design.md`'s "Payload contracts"
  * section - that cross-repo doc, not this case class, is the source of truth
  * order-service's future consumer (US-5.2) should read.
  */
final case class StockReservedEvent(
    inventoryId: String,
    sku: String,
    quantity: Int,
    timestamp: Instant
)

object StockReservedEvent {
  implicit val codec: Codec[StockReservedEvent] = deriveCodec
}

final case class StockReservationFailedEvent(
    inventoryId: String,
    sku: String,
    quantity: Int,
    timestamp: Instant
)

object StockReservationFailedEvent {
  implicit val codec: Codec[StockReservationFailedEvent] = deriveCodec
}
