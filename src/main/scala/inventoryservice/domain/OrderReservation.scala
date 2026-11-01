package inventoryservice.domain

import cats.data.NonEmptyList
import cats.syntax.all._

import java.util.UUID

/** Bulk order reservation domain (TD-7.1, ADR 0018) — contract in
  * `gluon/docs/system-design.md`'s `POST /inventorys/order-reservations`.
  * `orderItemId`s are order-service's own ids, stored and echoed; still no
  * order-domain coupling beyond the ledger key.
  */
final case class RequestedLine(orderItemId: String, sku: String, quantity: Int)

final case class ReservationLine(orderItemId: UUID, sku: String, quantity: Int)

/** A validated bulk reserve: built only via `ReserveOrder.validate`, so the
  * store never sees an empty, duplicated or non-positive request.
  */
final case class ReserveOrder(
    orderId: UUID,
    items: NonEmptyList[ReservationLine]
)

final case class OrderReservation(orderId: UUID, items: List[ReservationLine])

/** Why a line couldn't be reserved; `wire` is the contract's snake_case value.
  */
enum FailureReason(val wire: String) {
  case InsufficientStock extends FailureReason("insufficient_stock")
  case UnknownSku extends FailureReason("unknown_sku")
}

object FailureReason {
  def fromWire(raw: String): Option[FailureReason] =
    values.find(_.wire == raw)
}

final case class ReservationFailure(
    orderItemId: UUID,
    sku: String,
    reason: FailureReason
)

sealed trait OrderReservationError

final case class InvalidReservation(message: String)
    extends OrderReservationError

final case class ReservationRejected(failures: NonEmptyList[ReservationFailure])
    extends OrderReservationError

/** The order's reservation was already released: replaying it must not report
  * stock as held when none is.
  */
final case class ReservationReleased(orderId: UUID)
    extends OrderReservationError

object ReserveOrder {

  private def parseUuid(value: String, field: String) =
    Uuids.parse(value).toRight(InvalidReservation(s"$field must be a UUID"))

  def validate(
      orderId: String,
      items: List[RequestedLine]
  ): Either[InvalidReservation, ReserveOrder] =
    for {
      id <- parseUuid(orderId, "orderId")
      nel <- NonEmptyList
        .fromList(items)
        .toRight(InvalidReservation("items must not be empty"))
      lines <- nel.traverse { item =>
        parseUuid(item.orderItemId, "orderItemId").flatMap { itemId =>
          if (item.quantity > 0)
            Right(ReservationLine(itemId, item.sku, item.quantity))
          else Left(InvalidReservation("quantity must be positive"))
        }
      }
      _ <- Either.cond(
        lines.map(_.orderItemId).toList.distinct.size == lines.size,
        (),
        InvalidReservation("duplicate orderItemId")
      )
    } yield ReserveOrder(id, lines)

  /** Every failing line, in request order: a sku missing from `available` is
    * `UnknownSku`; a sku whose summed demand across lines exceeds its available
    * quantity fails on every one of its lines. Demand is summed in `Long`, so
    * lines that together exceed `Int.MaxValue` can't wrap round and pass.
    */
  def failures(
      lines: List[ReservationLine],
      available: Map[String, Int]
  ): List[ReservationFailure] = {
    val demand = lines.groupMapReduce(_.sku)(_.quantity.toLong)(_ + _)
    lines.flatMap { line =>
      available.get(line.sku) match {
        case None =>
          List(
            ReservationFailure(
              line.orderItemId,
              line.sku,
              FailureReason.UnknownSku
            )
          )
        case Some(qty) if demand.getOrElse(line.sku, 0L) > qty =>
          List(
            ReservationFailure(
              line.orderItemId,
              line.sku,
              FailureReason.InsufficientStock
            )
          )
        case Some(_) => Nil
      }
    }
  }
}
