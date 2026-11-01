package inventoryservice

import cats.{Monad, MonadThrow}
import cats.effect.Async
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import inventoryservice.domain._
import org.http4s.HttpRoutes
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.Schema.annotations.{description, encodedExample}
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

@description("A new stock record to create.")
final case class CreateInventoryRequest(
    @description("Unique stock-keeping unit code.")
    @encodedExample("watch-rolex-submariner")
    sku: String,
    @description("Units currently available, >= 0.")
    @encodedExample(25)
    quantityAvailable: Int
)

object CreateInventoryRequest {
  implicit val codec: Codec[CreateInventoryRequest] = deriveCodec
}

@description("A full stock-level update.")
final case class UpdateInventoryRequest(
    @description("Units currently available, >= 0.")
    @encodedExample(25)
    quantityAvailable: Int,
    @description(
      "Units held by reservations, >= 0. Admin override: written directly, bypassing the reservation ledger."
    )
    @encodedExample(3)
    quantityReserved: Int
)

object UpdateInventoryRequest {
  implicit val codec: Codec[UpdateInventoryRequest] = deriveCodec
}

@description("A stock record.")
final case class InventoryResponse(
    @description("Inventory record id (UUID).")
    @encodedExample("3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90")
    id: String,
    @description("Unique stock-keeping unit code.")
    @encodedExample("watch-rolex-submariner")
    sku: String,
    @description("Units currently available.")
    @encodedExample(25)
    quantityAvailable: Int,
    @description("Units held by active reservations.")
    @encodedExample(3)
    quantityReserved: Int,
    @description("UTC creation timestamp.")
    @encodedExample("2026-11-01T12:00:00Z")
    createdAt: java.time.Instant,
    @description("UTC last-update timestamp.")
    @encodedExample("2026-11-01T12:05:00Z")
    updatedAt: java.time.Instant
)

object InventoryResponse {
  implicit val codec: Codec[InventoryResponse] = deriveCodec

  def apply(entity: Inventory): InventoryResponse =
    InventoryResponse(
      InventoryId.render(entity.id),
      entity.sku,
      entity.quantityAvailable,
      entity.quantityReserved,
      entity.createdAt,
      entity.updatedAt
    )
}

@description("Error body.")
final case class ErrorResponse(
    @description("Human-readable reason.")
    @encodedExample("Inventory not found")
    error: String
)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

object InventoryRoutes {

  private val notFoundMessage = "Inventory not found"
  private val duplicateSkuMessage = "Inventory already exists for this sku"

  private val exampleInventory =
    InventoryResponse(
      "3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90",
      "watch-rolex-submariner",
      25,
      3,
      java.time.Instant.parse("2026-11-01T12:00:00Z"),
      java.time.Instant.parse("2026-11-01T12:05:00Z")
    )

  private val exampleCreate =
    CreateInventoryRequest("watch-rolex-submariner", 25)

  private val exampleUpdate = UpdateInventoryRequest(25, 3)

  private val idPath: EndpointInput.PathCapture[InventoryId] =
    path[InventoryId]("id")
      .description("Inventory record id (UUID).")
      .example(
        InventoryId.fromUuid(
          java.util.UUID.fromString("3f2b8c1e-7a4d-4e9b-9c2a-1d5e6f7a8b90")
        )
      )

  private def errorBody(
      what: String,
      example: ErrorResponse
  ): EndpointIO.Body[String, ErrorResponse] =
    jsonBody[ErrorResponse].description(what).example(example)

  private val notFoundVariant =
    oneOfVariant[InventoryNotFound.type](
      statusCode(StatusCode.NotFound)
        .and(
          errorBody(
            "No inventory record with this id.",
            ErrorResponse(notFoundMessage)
          )
        )
        .map(_ => InventoryNotFound)(_ => ErrorResponse(notFoundMessage))
    )

  private val invalidVariant =
    oneOfVariant[InvalidInventory](
      statusCode(StatusCode.BadRequest)
        .and(
          errorBody(
            "The body failed validation: a blank `sku` (create only) or a negative `quantityAvailable`/`quantityReserved`.",
            ErrorResponse("quantityAvailable must not be negative")
          )
        )
        .map(e => InvalidInventory(e.error))(i => ErrorResponse(i.message))
    )

  /** The sku isn't in the body, so decoding it back is a decode failure (only
    * tapir clients decode; this server never does).
    */
  private val duplicateSkuVariant =
    oneOfVariant[DuplicateSku](
      statusCode(StatusCode.Conflict)
        .and(
          errorBody(
            "A stock record already exists for this SKU.",
            ErrorResponse(duplicateSkuMessage)
          )
        )
        .mapDecode(_ =>
          DecodeResult.Error(
            duplicateSkuMessage,
            new IllegalArgumentException("sku is not in the body")
          ): DecodeResult[DuplicateSku]
        )(_ => ErrorResponse(duplicateSkuMessage))
    )

  private val notFoundOutput: EndpointOutput[InventoryError] =
    oneOf[InventoryError](notFoundVariant)

  private val createInventoryEndpoint: PublicEndpoint[
    CreateInventoryRequest,
    InventoryError,
    InventoryResponse,
    Any
  ] =
    endpoint.post
      .in("inventorys")
      .in(jsonBody[CreateInventoryRequest].example(exampleCreate))
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[InventoryResponse].example(exampleInventory))
      .errorOut(oneOf[InventoryError](invalidVariant, duplicateSkuVariant))
      .summary("Create inventory")
      .description(
        "Creates a stock record for a SKU; `quantityReserved` starts at 0. Body is validated first: blank `sku` or a negative quantity → 400. SKU is unique: an existing SKU → 409. Not idempotent — each successful call creates a new record."
      )
      .tag("inventorys")

  private val updateErrorOutput: EndpointOutput[InventoryError] =
    oneOf[InventoryError](invalidVariant, notFoundVariant)

  private val getInventoryEndpoint: PublicEndpoint[
    InventoryId,
    InventoryError,
    InventoryResponse,
    Any
  ] =
    endpoint.get
      .in("inventorys" / idPath)
      .out(jsonBody[InventoryResponse].example(exampleInventory))
      .errorOut(notFoundOutput)
      .summary("Get inventory")
      .description(
        "One stock record by id. 404 for an unknown id; a malformed UUID is rejected by the path decoder as 400."
      )
      .tag("inventorys")

  private val updateInventoryEndpoint: PublicEndpoint[
    (InventoryId, UpdateInventoryRequest),
    InventoryError,
    InventoryResponse,
    Any
  ] =
    endpoint.patch
      .in("inventorys" / idPath)
      .in(jsonBody[UpdateInventoryRequest].example(exampleUpdate))
      .out(jsonBody[InventoryResponse].example(exampleInventory))
      .errorOut(updateErrorOutput)
      .summary("Update inventory")
      .description(
        "Sets both quantities; every field is required. Idempotent. Admin override: writes `quantityReserved` directly and does not touch the `order_reservations` ledger, so it can drift from the active lines (a later release may then drive it negative). Body is checked first (400), then existence (404)."
      )
      .tag("inventorys")

  private val replaceInventoryEndpoint: PublicEndpoint[
    (InventoryId, UpdateInventoryRequest),
    InventoryError,
    InventoryResponse,
    Any
  ] =
    endpoint.put
      .in("inventorys" / idPath)
      .in(jsonBody[UpdateInventoryRequest].example(exampleUpdate))
      .out(jsonBody[InventoryResponse].example(exampleInventory))
      .errorOut(updateErrorOutput)
      .summary("Update inventory (replace)")
      .description(
        "Same as PATCH — both fields required, same admin-override caveat; kept for PUT clients."
      )
      .tag("inventorys")

  private val deleteInventoryEndpoint
      : PublicEndpoint[InventoryId, InventoryError, Unit, Any] =
    endpoint.delete
      .in("inventorys" / idPath)
      .out(statusCode(StatusCode.NoContent))
      .errorOut(notFoundOutput)
      .summary("Delete inventory")
      .description(
        "Hard-deletes the record; repeat → 404. Past reservation ledger lines for the SKU are left untouched."
      )
      .tag("inventorys")

  private val listInventoryEndpoint: PublicEndpoint[
    Option[String],
    Unit,
    List[InventoryResponse],
    Any
  ] =
    endpoint.get
      .in("inventorys")
      .in(
        query[Option[String]]("sku")
          .description("Exact SKU to filter by.")
          .example(Some("watch-rolex-submariner"))
      )
      .out(jsonBody[List[InventoryResponse]].example(List(exampleInventory)))
      .summary("List inventory")
      .description(
        "Stock records ordered by SKU. Optional exact `sku` filter; an unknown filter returns `[]`. Never 404s."
      )
      .tag("inventorys")

  def createInventoryServerEndpoint[F[_]: MonadThrow](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    createInventoryEndpoint.serverLogic[F] { req =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> "/inventorys"
          )
        )("Received request")
        result <- StockLevels.create(req.sku, req.quantityAvailable) match {
          case Left(invalid) =>
            logger
              .warn(Map("error" -> invalid.message))(
                "Invalid inventory request"
              )
              .as(Left(invalid))
          case Right(stock) =>
            store
              .create(stock)
              .onError { case error =>
                logger.error(Map.empty, error)(
                  "Persisting the inventory failed"
                )
              }
              .flatMap {
                case Right(entity) =>
                  logger
                    .info(Map("inventory_id" -> InventoryId.render(entity.id)))(
                      "Request completed"
                    )
                    .as(Right(InventoryResponse(entity)))
                case Left(duplicate) =>
                  logger
                    .warn(Map("sku" -> duplicate.sku))("Duplicate sku")
                    .as(Left(duplicate))
              }
        }
      } yield result
    }

  def getInventoryServerEndpoint[F[_]: Monad](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    getInventoryEndpoint.serverLogic[F] { id =>
      val renderedId = InventoryId.render(id)
      for {
        _ <- logger.info(
          Map(
            "method" -> "GET",
            "path" -> s"/inventorys/$renderedId",
            "inventory_id" -> renderedId
          )
        )("Received request")
        result <- store.get(id).flatMap {
          case Some(entity) =>
            logger
              .info(Map("inventory_id" -> renderedId))("Request completed")
              .as(Right(InventoryResponse(entity)))
          case None =>
            logger
              .warn(Map("inventory_id" -> renderedId))("Inventory not found")
              .as(Left(InventoryNotFound))
        }
      } yield result
    }

  /** Shared handler for `PATCH` (partial update) and `PUT` (full replace) —
    * both call `InventoryStore.update` with the same required update body; only
    * the logged HTTP method differs.
    */
  private def updateLogic[F[_]: Monad](
      store: InventoryStore[F],
      logger: StructuredLogger[F],
      httpMethod: String
  )(
      id: InventoryId,
      req: UpdateInventoryRequest
  ): F[Either[InventoryError, InventoryResponse]] = {
    val renderedId = InventoryId.render(id)
    for {
      _ <- logger.info(
        Map(
          "method" -> httpMethod,
          "path" -> s"/inventorys/$renderedId",
          "inventory_id" -> renderedId
        )
      )("Received request")
      result <- StockLevels.update(
        req.quantityAvailable,
        req.quantityReserved
      ) match {
        case Left(invalid) =>
          logger
            .warn(
              Map("inventory_id" -> renderedId, "error" -> invalid.message)
            )(
              "Invalid inventory request"
            )
            .as(Left(invalid))
        case Right(levels) =>
          store.update(id, levels).flatMap {
            case Some(entity) =>
              logger
                .info(Map("inventory_id" -> renderedId))("Request completed")
                .as(Right(InventoryResponse(entity)))
            case None =>
              logger
                .warn(Map("inventory_id" -> renderedId))("Inventory not found")
                .as(Left(InventoryNotFound))
          }
      }
    } yield result
  }

  def updateInventoryServerEndpoint[F[_]: Monad](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    updateInventoryEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PATCH")(id, req)
    }

  def replaceInventoryServerEndpoint[F[_]: Monad](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    replaceInventoryEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PUT")(id, req)
    }

  def deleteInventoryServerEndpoint[F[_]: Monad](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    deleteInventoryEndpoint.serverLogic[F] { id =>
      val renderedId = InventoryId.render(id)
      for {
        _ <- logger.info(
          Map(
            "method" -> "DELETE",
            "path" -> s"/inventorys/$renderedId",
            "inventory_id" -> renderedId
          )
        )("Received request")
        result <- store.delete(id).flatMap {
          case true =>
            logger
              .info(Map("inventory_id" -> renderedId))("Request completed")
              .as(Right(()))
          case false =>
            logger
              .warn(Map("inventory_id" -> renderedId))("Inventory not found")
              .as(Left(InventoryNotFound))
        }
      } yield result
    }

  def listInventoryServerEndpoint[F[_]: Monad](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    listInventoryEndpoint.serverLogicSuccess[F] { skuFilter =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "GET",
            "path" -> "/inventorys"
          ) ++ skuFilter.map("sku" -> _)
        )("Received request")
        entities <- store.list(skuFilter)
        _ <- logger.info(
          Map("count" -> entities.size.toString)
        )("Request completed")
      } yield entities.map(InventoryResponse.apply)
    }

  /** Every inventory and order-reservation endpoint: the one list both `routes`
    * and `AppRoutes` serve.
    */
  def serverEndpoints[F[_]: MonadThrow](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): List[ServerEndpoint[Any, F]] =
    List(
      createInventoryServerEndpoint(store, logger),
      getInventoryServerEndpoint(store, logger),
      updateInventoryServerEndpoint(store, logger),
      replaceInventoryServerEndpoint(store, logger),
      deleteInventoryServerEndpoint(store, logger),
      listInventoryServerEndpoint(store, logger)
    ) ++ OrderReservationRoutes.serverEndpoints(store, logger)

  def routes[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(serverEndpoints(store, logger))
}
