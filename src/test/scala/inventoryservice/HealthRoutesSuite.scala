package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite
import inventoryservice.domain.InventoryId
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}

class HealthRoutesSuite extends CatsEffectSuite {

  private val readyStore: InventoryStore[IO] =
    new InventoryStore[IO] {
      def create(sku: String, quantityAvailable: Int): IO[Inventory] =
        IO.raiseError(new NotImplementedError())
      def get(id: InventoryId): IO[Option[Inventory]] = IO.pure(None)
      def update(
          id: InventoryId,
          quantityAvailable: Int,
          quantityReserved: Int
      ): IO[Option[Inventory]] = IO.pure(None)
      def delete(id: InventoryId): IO[Boolean] = IO.pure(false)
      def list(sku: Option[String]): IO[List[Inventory]] = IO.pure(Nil)
      def reserveOrder(
          command: ReserveOrder
      ): IO[Either[OrderReservationError, OrderReservation]] =
        IO.raiseError(new NotImplementedError())
      def releaseOrder(orderId: java.util.UUID): IO[Unit] =
        IO.raiseError(new NotImplementedError())
      def ping: IO[Boolean] = IO.pure(true)
    }

  private val notReadyStore: InventoryStore[IO] =
    new InventoryStore[IO] {
      def create(sku: String, quantityAvailable: Int): IO[Inventory] =
        IO.raiseError(new NotImplementedError())
      def get(id: InventoryId): IO[Option[Inventory]] = IO.pure(None)
      def update(
          id: InventoryId,
          quantityAvailable: Int,
          quantityReserved: Int
      ): IO[Option[Inventory]] = IO.pure(None)
      def delete(id: InventoryId): IO[Boolean] = IO.pure(false)
      def list(sku: Option[String]): IO[List[Inventory]] = IO.pure(Nil)
      def reserveOrder(
          command: ReserveOrder
      ): IO[Either[OrderReservationError, OrderReservation]] =
        IO.raiseError(new NotImplementedError())
      def releaseOrder(orderId: java.util.UUID): IO[Unit] =
        IO.raiseError(new NotImplementedError())
      def ping: IO[Boolean] = IO.pure(false)
    }

  test("GET /health returns 200") {
    val routes = HealthRoutes.routes[IO](readyStore)
    for {
      response <- routes.orNotFound.run(Request[IO](Method.GET, uri"/health"))
    } yield assertEquals(response.status, Status.Ok)
  }

  test("GET /health/ready returns 200 when the store is ready") {
    val routes = HealthRoutes.routes[IO](readyStore)
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/health/ready")
      )
    } yield assertEquals(response.status, Status.Ok)
  }

  test("GET /health/ready returns 503 when the store is not ready") {
    val routes = HealthRoutes.routes[IO](notReadyStore)
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/health/ready")
      )
    } yield assertEquals(response.status, Status.ServiceUnavailable)
  }
}
