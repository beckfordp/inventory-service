package inventoryservice

import cats.effect.Async
import cats.syntax.all._
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.http4s.HttpRoutes
import org.typelevel.log4cats.StructuredLogger
import sttp.model.StatusCode
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter

final case class CreateInventoryRequest(sku: String, quantityAvailable: Int)

object CreateInventoryRequest {
  implicit val codec: Codec[CreateInventoryRequest] = deriveCodec
}

final case class UpdateInventoryRequest(
    quantityAvailable: Int,
    quantityReserved: Int
)

object UpdateInventoryRequest {
  implicit val codec: Codec[UpdateInventoryRequest] = deriveCodec
}

final case class InventoryResponse(
    id: String,
    sku: String,
    quantityAvailable: Int,
    quantityReserved: Int,
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)

object InventoryResponse {
  implicit val codec: Codec[InventoryResponse] = deriveCodec

  def apply(entity: Inventory): InventoryResponse =
    InventoryResponse(
      entity.id,
      entity.sku,
      entity.quantityAvailable,
      entity.quantityReserved,
      entity.createdAt,
      entity.updatedAt
    )
}

final case class ErrorResponse(error: String)

object ErrorResponse {
  implicit val codec: Codec[ErrorResponse] = deriveCodec
}

object InventoryRoutes {

  private val createInventoryEndpoint: PublicEndpoint[
    CreateInventoryRequest,
    Unit,
    InventoryResponse,
    Any
  ] =
    endpoint.post
      .in("inventorys")
      .in(jsonBody[CreateInventoryRequest])
      .out(statusCode(StatusCode.Created))
      .out(jsonBody[InventoryResponse])

  private val notFoundOutput: EndpointOutput[InventoryError] =
    statusCode(StatusCode.NotFound)
      .and(jsonBody[ErrorResponse])
      .map[InventoryError](_ => InventoryNotFound)(_ =>
        ErrorResponse("Inventory not found")
      )

  private val getInventoryEndpoint: PublicEndpoint[
    String,
    InventoryError,
    InventoryResponse,
    Any
  ] =
    endpoint.get
      .in("inventorys" / path[String]("id"))
      .out(jsonBody[InventoryResponse])
      .errorOut(notFoundOutput)

  private val updateInventoryEndpoint: PublicEndpoint[
    (String, UpdateInventoryRequest),
    InventoryError,
    InventoryResponse,
    Any
  ] =
    endpoint.patch
      .in("inventorys" / path[String]("id"))
      .in(jsonBody[UpdateInventoryRequest])
      .out(jsonBody[InventoryResponse])
      .errorOut(notFoundOutput)

  private val replaceInventoryEndpoint: PublicEndpoint[
    (String, UpdateInventoryRequest),
    InventoryError,
    InventoryResponse,
    Any
  ] =
    endpoint.put
      .in("inventorys" / path[String]("id"))
      .in(jsonBody[UpdateInventoryRequest])
      .out(jsonBody[InventoryResponse])
      .errorOut(notFoundOutput)

  private val deleteInventoryEndpoint
      : PublicEndpoint[String, InventoryError, Unit, Any] =
    endpoint.delete
      .in("inventorys" / path[String]("id"))
      .out(statusCode(StatusCode.NoContent))
      .errorOut(notFoundOutput)

  def serverEndpoint[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    createInventoryEndpoint.serverLogicSuccess[F] { req =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "POST",
            "path" -> "/inventorys"
          )
        )("Received request")
        entity <- store.create(req.sku, req.quantityAvailable).onError {
          case error =>
            logger.error(Map.empty, error)("Persisting the inventory failed")
        }
        _ <- logger.info(
          Map("inventory_id" -> entity.id)
        )("Request completed")
      } yield InventoryResponse(entity)
    }

  def getInventoryServerEndpoint[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    getInventoryEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "GET",
            "path" -> s"/inventorys/$id",
            "inventory_id" -> id
          )
        )(
          "Received request"
        )
        result <- store.get(id).flatMap {
          case Some(entity) =>
            logger
              .info(Map("inventory_id" -> id))("Request completed")
              .as(Right(InventoryResponse(entity)))
          case None =>
            logger
              .warn(Map("inventory_id" -> id))("Inventory not found")
              .as(Left(InventoryNotFound))
        }
      } yield result
    }

  /** Shared handler for `PATCH` (partial update) and `PUT` (full replace) —
    * both call `InventoryStore.update` with the same required update body; only
    * the logged HTTP method differs.
    */
  private def updateLogic[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F],
      httpMethod: String
  )(
      id: String,
      req: UpdateInventoryRequest
  ): F[Either[InventoryError, InventoryResponse]] =
    for {
      _ <- logger.info(
        Map(
          "method" -> httpMethod,
          "path" -> s"/inventorys/$id",
          "inventory_id" -> id
        )
      )("Received request")
      result <- store
        .update(id, req.quantityAvailable, req.quantityReserved)
        .flatMap {
          case Some(entity) =>
            logger
              .info(Map("inventory_id" -> id))("Request completed")
              .as(Right(InventoryResponse(entity)))
          case None =>
            logger
              .warn(Map("inventory_id" -> id))("Inventory not found")
              .as(Left(InventoryNotFound))
        }
    } yield result

  def updateInventoryServerEndpoint[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    updateInventoryEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PATCH")(id, req)
    }

  def replaceInventoryServerEndpoint[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    replaceInventoryEndpoint.serverLogic[F] { case (id, req) =>
      updateLogic(store, logger, "PUT")(id, req)
    }

  def deleteInventoryServerEndpoint[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): ServerEndpoint[Any, F] =
    deleteInventoryEndpoint.serverLogic[F] { id =>
      for {
        _ <- logger.info(
          Map(
            "method" -> "DELETE",
            "path" -> s"/inventorys/$id",
            "inventory_id" -> id
          )
        )("Received request")
        result <- store.delete(id).flatMap {
          case true =>
            logger
              .info(Map("inventory_id" -> id))("Request completed")
              .as(Right(()))
          case false =>
            logger
              .warn(Map("inventory_id" -> id))("Inventory not found")
              .as(Left(InventoryNotFound))
        }
      } yield result
    }

  def routes[F[_]: Async](
      store: InventoryStore[F],
      logger: StructuredLogger[F]
  ): HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      List(
        serverEndpoint(store, logger),
        getInventoryServerEndpoint(store, logger),
        updateInventoryServerEndpoint(store, logger),
        replaceInventoryServerEndpoint(store, logger),
        deleteInventoryServerEndpoint(store, logger)
      )
    )
}
