package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite
import inventoryservice.domain._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}

class HealthRoutesSuite extends CatsEffectSuite {

  private val readyStore: InventoryStore[IO] =
    new InventoryStore[IO] {
      def create(stock: NewStock): IO[Either[DuplicateSku, Inventory]] =
        IO.raiseError(new NotImplementedError())
      def get(id: InventoryId): IO[Option[Inventory]] = IO.pure(None)
      def update(
          id: InventoryId,
          levels: StockUpdate
      ): IO[Option[Inventory]] = IO.pure(None)
      def delete(id: InventoryId): IO[Boolean] = IO.pure(false)
      def list(sku: Option[String]): IO[List[Inventory]] = IO.pure(Nil)
      def reserveOrder(
          command: ReserveOrder
      ): IO[Either[ReservationConflict, OrderReservation]] =
        IO.raiseError(new NotImplementedError())
      def releaseOrder(
          orderId: java.util.UUID
      ): IO[ReleaseOutcome] =
        IO.raiseError(new NotImplementedError())
      def markShipped(orderId: java.util.UUID): IO[ShipOutcome] =
        IO.raiseError(new NotImplementedError())
      def flagReconciliation(
          orderId: java.util.UUID,
          reason: String
      ): IO[Boolean] = IO.pure(false)
      def reconciliationReason(
          orderId: java.util.UUID
      ): IO[Option[String]] = IO.pure(None)
      def listReconciliations(
          status: Option[ReconciliationStatus],
          orderId: Option[java.util.UUID]
      ): IO[List[Reconciliation]] = IO.pure(Nil)
      def resolveReconciliation(
          orderId: java.util.UUID,
          operatorId: String,
          note: Option[String],
          resolvedAt: java.time.Instant
      ): IO[ResolveReconciliationOutcome] =
        IO.pure(ResolveReconciliationOutcome.NotFound)
      def listReservations(
          orderId: Option[java.util.UUID],
          status: Option[String]
      ): IO[List[ReservationLedgerEntry]] = IO.pure(Nil)
      def ping: IO[Boolean] = IO.pure(true)
    }

  private val notReadyStore: InventoryStore[IO] =
    new InventoryStore[IO] {
      def create(stock: NewStock): IO[Either[DuplicateSku, Inventory]] =
        IO.raiseError(new NotImplementedError())
      def get(id: InventoryId): IO[Option[Inventory]] = IO.pure(None)
      def update(
          id: InventoryId,
          levels: StockUpdate
      ): IO[Option[Inventory]] = IO.pure(None)
      def delete(id: InventoryId): IO[Boolean] = IO.pure(false)
      def list(sku: Option[String]): IO[List[Inventory]] = IO.pure(Nil)
      def reserveOrder(
          command: ReserveOrder
      ): IO[Either[ReservationConflict, OrderReservation]] =
        IO.raiseError(new NotImplementedError())
      def releaseOrder(
          orderId: java.util.UUID
      ): IO[ReleaseOutcome] =
        IO.raiseError(new NotImplementedError())
      def markShipped(orderId: java.util.UUID): IO[ShipOutcome] =
        IO.raiseError(new NotImplementedError())
      def flagReconciliation(
          orderId: java.util.UUID,
          reason: String
      ): IO[Boolean] = IO.pure(false)
      def reconciliationReason(
          orderId: java.util.UUID
      ): IO[Option[String]] = IO.pure(None)
      def listReconciliations(
          status: Option[ReconciliationStatus],
          orderId: Option[java.util.UUID]
      ): IO[List[Reconciliation]] = IO.pure(Nil)
      def resolveReconciliation(
          orderId: java.util.UUID,
          operatorId: String,
          note: Option[String],
          resolvedAt: java.time.Instant
      ): IO[ResolveReconciliationOutcome] =
        IO.pure(ResolveReconciliationOutcome.NotFound)
      def listReservations(
          orderId: Option[java.util.UUID],
          status: Option[String]
      ): IO[List[ReservationLedgerEntry]] = IO.pure(Nil)
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
