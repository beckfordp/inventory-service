package inventoryservice

import cats.data.NonEmptyList
import cats.Monad
import cats.effect.Async
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import inventoryservice.domain._
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.Schema.annotations.{description, encodedExample}
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint

@description("One line to reserve.")
final case class ReserveOrderItemRequest(
    @description("order-service line id (UUID).")
    @encodedExample("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
    orderItemId: String,
    @description("SKU to reserve.")
    @encodedExample("watch-rolex-submariner")
    sku: String,
    @description("Units to reserve, > 0.")
    @encodedExample(2)
    quantity: Int
)

object ReserveOrderItemRequest {
  implicit val codec: Codec[ReserveOrderItemRequest] = deriveCodec
}

@description("A bulk stock reservation for one order.")
final case class ReserveOrderRequest(
    @description("Order id (UUID); the idempotency key.")
    @encodedExample("5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f")
    orderId: String,
    @description("Lines to reserve, at least one; all-or-nothing.")
    items: List[ReserveOrderItemRequest]
)

object ReserveOrderRequest {
  implicit val codec: Codec[ReserveOrderRequest] = deriveCodec
}

@description("One successfully reserved line.")
final case class ReservationLineResponse(
    @description("order-service line id (UUID).")
    @encodedExample("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
    orderItemId: String,
    @description("SKU held.")
    @encodedExample("watch-rolex-submariner")
    sku: String,
    @description("Units held.")
    @encodedExample(2)
    quantity: Int
)

object ReservationLineResponse {
  implicit val codec: Codec[ReservationLineResponse] = deriveCodec
}

@description("The lines the order now holds.")
final case class OrderReservationResponse(
    @description("Order id (UUID).")
    @encodedExample("5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f")
    orderId: String,
    @description("Every line the order now holds.")
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

@description("A line that couldn't be reserved.")
final case class ReservationFailureResponse(
    @description("Failed line id (UUID).")
    @encodedExample("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
    orderItemId: String,
    @description("SKU that could not be reserved.")
    @encodedExample("watch-rolex-submariner")
    sku: String,
    @description(
      "`insufficient_stock` (summed demand exceeds available) or `unknown_sku` (no stock record)."
    )
    @encodedExample("insufficient_stock")
    reason: String
)

object ReservationFailureResponse {
  implicit val codec: Codec[ReservationFailureResponse] = deriveCodec
}

@description("Error body for lines that couldn't be reserved.")
final case class ReservationRejectedResponse(
    @description("Human-readable summary; always `Insufficient stock`.")
    @encodedExample("Insufficient stock")
    error: String,
    @description("Every failed line, in request order.")
    failures: List[ReservationFailureResponse]
)

object ReservationRejectedResponse {
  implicit val codec: Codec[ReservationRejectedResponse] = deriveCodec
}

/** Bulk reserve and release-by-order (TD-7.1/7.2, ADR 0018) — contracts in
  * `gluon/docs/system-design.md`. Neither publishes an event.
  */
object OrderReservationRoutes {

  private val exampleItem = ReserveOrderItemRequest(
    "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
    "watch-rolex-submariner",
    2
  )

  private val exampleRequest = ReserveOrderRequest(
    "5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f",
    List(exampleItem)
  )

  private val exampleLine = ReservationLineResponse(
    "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
    "watch-rolex-submariner",
    2
  )

  private val exampleResponse = OrderReservationResponse(
    "5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f",
    List(exampleLine)
  )

  private val exampleFailure = ReservationFailureResponse(
    "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
    "watch-rolex-submariner",
    "insufficient_stock"
  )

  private val exampleRejected = ReservationRejectedResponse(
    "Insufficient stock",
    List(exampleFailure)
  )

  private def toFailureResponse(f: ReservationFailure) =
    ReservationFailureResponse(f.orderItemId.toString, f.sku, f.reason.wire)

  /** Decoding the 409 body is only exercised by tapir clients, never by this
    * server; an unparseable body is a decode failure, not a guessed value.
    */
  private[inventoryservice] def fromRejectedResponse(
      body: ReservationRejectedResponse
  ): DecodeResult[ReservationRejected] =
    body.failures
      .traverse { f =>
        (Uuids.parse(f.orderItemId), FailureReason.fromWire(f.reason))
          .mapN(ReservationFailure(_, f.sku, _))
      }
      .flatMap(NonEmptyList.fromList)
      .fold[DecodeResult[ReservationRejected]](
        DecodeResult.Error(body.error, new IllegalArgumentException(body.error))
      )(failures => DecodeResult.Value(ReservationRejected(failures)))

  private val releasedMessage = "Order reservation already released"

  private val reserveOrderErrorOutput: EndpointOutput[OrderReservationError] =
    oneOf[OrderReservationError](
      oneOfVariant[InvalidReservation](
        statusCode(StatusCode.BadRequest)
          .and(
            jsonBody[ErrorResponse]
              .description(
                "The reservation body failed validation: `orderId` or an `orderItemId` isn't a UUID, `items` is empty, a line's `quantity` is not positive, or an `orderItemId` repeats."
              )
              .example(ErrorResponse("orderId must be a UUID"))
          )
          .map(e => InvalidReservation(e.error))(i => ErrorResponse(i.message))
      ),
      oneOfVariant[ReservationReleased](
        statusCode(StatusCode.Conflict)
          .and(
            jsonBody[ErrorResponse]
              .description(
                "The order's reservation was already released; stock is not held."
              )
              .example(ErrorResponse(releasedMessage))
          )
          .mapDecode(_ =>
            DecodeResult.Error(
              releasedMessage,
              new IllegalArgumentException("orderId is not in the body")
            ): DecodeResult[ReservationReleased]
          )(_ => ErrorResponse(releasedMessage))
      ),
      oneOfVariant[ReservationRejected](
        statusCode(StatusCode.Conflict)
          .and(
            jsonBody[ReservationRejectedResponse]
              .description(
                "One or more lines couldn't be reserved; nothing was reserved."
              )
              .example(exampleRejected)
          )
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
      .in(jsonBody[ReserveOrderRequest].example(exampleRequest))
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[OrderReservationResponse].example(exampleResponse))
      .errorOut(reserveOrderErrorOutput)
      .summary("Reserve stock for an order")
      .description(
        "All-or-nothing bulk reservation, idempotent per `orderId`. Validates the body (UUIDs, non-empty `items`, positive `quantity`, no duplicate `orderItemId`) → 400. Reserves every line in one transaction, summing demand per SKU in `Long`, or reserves nothing and returns 409 listing every failed line. An order that still holds active lines returns its original lines and reserves nothing more (201); a released order returns 409 rather than reporting stock it no longer holds. No event is published."
      )
      .tag("order-reservations")

  def reserveOrderServerEndpoint[F[_]: Monad](
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
              case Left(error) =>
                val detail = error match {
                  case ReservationRejected(failures) =>
                    "failed_lines" -> failures.size.toString
                  case ReservationReleased(_) => "error" -> "released"
                }
                logger
                  .warn(context + detail)("Reservation rejected")
                  .as(Left(error))
            }
        }
      } yield result
    }

  private val releaseOrderEndpoint: PublicEndpoint[String, Unit, Unit, Any] =
    endpoint.post
      .in(
        "inventorys" / "order-reservations" / path[String]("orderId")
          .description(
            "Order id; any string is accepted and a non-UUID is a no-op."
          )
          .example("5c1d9e2f-8b3a-4c7d-9e0f-2a1b3c4d5e6f") / "release"
      )
      .out(statusCode(StatusCode.NoContent))
      .summary("Release an order's reservation")
      .description(
        "Returns every active line to `quantityAvailable` and marks its ledger lines released. Always 204: unknown, already-released or non-UUID `orderId` holds nothing, so compensation is safe to retry. No event is published."
      )
      .tag("order-reservations")

  /** Always `204`: an unknown, already-released or non-UUID `orderId` holds
    * nothing, so there is nothing to release — compensation stays safe to
    * retry.
    */
  def releaseOrderServerEndpoint[F[_]: Monad](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    releaseOrderEndpoint.serverLogicSuccess[F] { orderId =>
      val context = Map("order_id" -> orderId)
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> s"/inventorys/order-reservations/$orderId/release"
          ) ++ context
        )("Received request")
        _ <- Uuids.parse(orderId).traverse_(store.releaseOrder)
        _ <- logger.info(context)("Request completed")
      } yield ()
    }

  def serverEndpoints[F[_]: Monad](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Any, F]] =
    List(
      reserveOrderServerEndpoint(store, logger),
      releaseOrderServerEndpoint(store, logger)
    )
}
