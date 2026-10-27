package inventoryservice

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import scala.util.Try

final case class ReserveOrderItemRequest(
    orderItemId: String,
    sku: String,
    quantity: Int
)

object ReserveOrderItemRequest {
  implicit val codec: Codec[ReserveOrderItemRequest] = deriveCodec
}

final case class ReserveOrderRequest(
    orderId: String,
    items: List[ReserveOrderItemRequest]
)

object ReserveOrderRequest {
  implicit val codec: Codec[ReserveOrderRequest] = deriveCodec
}

final case class ReservationLineResponse(
    orderItemId: String,
    sku: String,
    quantity: Int
)

object ReservationLineResponse {
  implicit val codec: Codec[ReservationLineResponse] = deriveCodec
}

final case class OrderReservationResponse(
    orderId: String,
    items: List[ReservationLineResponse]
)

object OrderReservationResponse {
  implicit val codec: Codec[OrderReservationResponse] = deriveCodec

  def apply(reservation: OrderReservation): OrderReservationResponse =
    OrderReservationResponse(
      reservation.orderId.toString,
      reservation.items.map(l =>
        ReservationLineResponse(l.orderItemId.toString, l.sku, l.quantity)
      )
    )
}

final case class ReservationFailureResponse(
    orderItemId: String,
    sku: String,
    reason: String
)

object ReservationFailureResponse {
  implicit val codec: Codec[ReservationFailureResponse] = deriveCodec
}

final case class ReservationRejectedResponse(
    error: String,
    failures: List[ReservationFailureResponse]
)

object ReservationRejectedResponse {
  implicit val codec: Codec[ReservationRejectedResponse] = deriveCodec
}

/** Bulk reserve and release-by-order (TD-7.1/7.2, ADR 0018) — contracts in
  * `gluon/docs/system-design.md`. Neither publishes an event.
  */
object OrderReservationRoutes {

  private def toFailureResponse(f: ReservationFailure) =
    ReservationFailureResponse(f.orderItemId.toString, f.sku, f.reason.wire)

  /** Decoding the 409 body is only exercised by tapir clients, never by this
    * server; an unparseable body is a decode failure, not a guessed value.
    */
  private def fromRejectedResponse(
      body: ReservationRejectedResponse
  ): DecodeResult[ReservationRejected] =
    body.failures
      .traverse { f =>
        val reason =
          List(FailureReason.InsufficientStock, FailureReason.UnknownSku)
            .find(_.wire == f.reason)
        (Try(UUID.fromString(f.orderItemId)).toOption, reason).mapN(
          ReservationFailure(_, f.sku, _)
        )
      }
      .flatMap(NonEmptyList.fromList)
      .fold[DecodeResult[ReservationRejected]](
        DecodeResult.Error(body.error, new IllegalArgumentException(body.error))
      )(failures => DecodeResult.Value(ReservationRejected(failures)))

  private val reserveOrderErrorOutput: EndpointOutput[OrderReservationError] =
    oneOf[OrderReservationError](
      oneOfVariant[InvalidReservation](
        statusCode(StatusCode.BadRequest)
          .and(jsonBody[ErrorResponse])
          .map(e => InvalidReservation(e.error))(i => ErrorResponse(i.message))
      ),
      oneOfVariant[ReservationRejected](
        statusCode(StatusCode.Conflict)
          .and(jsonBody[ReservationRejectedResponse])
          .mapDecode(fromRejectedResponse)(r =>
            ReservationRejectedResponse(
              "Insufficient stock",
              r.failures.toList.map(toFailureResponse)
            )
          )
      )
    )

  private val reserveOrderEndpoint: PublicEndpoint[
    ReserveOrderRequest,
    OrderReservationError,
    OrderReservationResponse,
    Any
  ] =
    endpoint.post
      .in("inventorys" / "order-reservations")
      .in(jsonBody[ReserveOrderRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[OrderReservationResponse])
      .errorOut(reserveOrderErrorOutput)

  def reserveOrderServerEndpoint[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    reserveOrderEndpoint.serverLogic[F] { req =>
      val context = Map("order_id" -> req.orderId)
      for {
        _ <- logger.info(
          Map("method" -> "POST", "path" -> "/inventorys/order-reservations") ++
            context
        )("Received request")
        result <- ReserveOrder.validate(
          req.orderId,
          req.items.map(i => RequestedLine(i.orderItemId, i.sku, i.quantity))
        ) match {
          case Left(invalid) =>
            logger
              .warn(context + ("error" -> invalid.message))(
                "Invalid reservation request"
              )
              .as(Left(invalid: OrderReservationError))
          case Right(command) =>
            store.reserveOrder(command).flatMap {
              case Right(reservation) =>
                logger
                  .info(context)("Request completed")
                  .as(Right(OrderReservationResponse(reservation)))
              case Left(rejected) =>
                logger
                  .warn(
                    context + ("failed_lines" -> rejected.failures.size.toString)
                  )("Reservation rejected")
                  .as(Left(rejected: OrderReservationError))
            }
        }
      } yield result
    }

  def serverEndpoints[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Any, F]] =
    List(reserveOrderServerEndpoint(store, logger))
}
