package inventoryservice

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

import java.time.Instant

/** Payload shape pinned in `gluon/docs/system-design.md`'s "Payload contracts"
  * section - that cross-repo doc, not this case class, is the source of truth
  * order-service's consumer (US-5.2) should read. Correlation is by
  * `orderItemId` - order-service's own `order_items.id`, echoed verbatim from
  * the `POST /inventorys/reservations` request. Opaque to inventory-service; no
  * order-domain coupling implied. (An earlier draft correlated by `sku` alone,
  * dropped once it became clear that couldn't disambiguate concurrent orders -
  * or line items - reserving the same sku. A draft before that used
  * inventory-service's own internal id, dropped since the failure path has no
  * `Inventory` record to pull one from.)
  */
final case class StockReservedEvent(
    orderItemId: String,
    sku: String,
    quantity: Int,
    timestamp: Instant
)

object StockReservedEvent {
  implicit val codec: Codec[StockReservedEvent] = deriveCodec
}

final case class StockReservationFailedEvent(
    orderItemId: String,
    sku: String,
    quantity: Int,
    timestamp: Instant
)

object StockReservationFailedEvent {
  implicit val codec: Codec[StockReservationFailedEvent] = deriveCodec
}
