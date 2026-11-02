package inventoryservice

import cats.data.NonEmptyList
import cats.effect.IO
import inventoryservice.domain.{
  NewStock,
  ReconciliationStatus,
  ReservationLine,
  ReserveOrder
}
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Method, Request, Status, Uri}
import org.typelevel.log4cats.noop.NoOpLogger
import sttp.tapir.server.http4s.Http4sServerInterpreter

import java.util.UUID

class InventoryReconciliationSuite extends CatsEffectSuite {

  private val auth = OperatorAuth(Some("test-token"), "op-1")

  private def routes(store: InventoryStore[IO]): HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(
      List(
        ReconciliationRoutes.listServerEndpoint[IO](store, NoOpLogger[IO]),
        ReconciliationRoutes.resolveServerEndpoint[IO](
          store,
          auth,
          NoOpLogger[IO]
        )
      )
    )

  /** The inventory flag requires a ledger row; reserve one line per order. */
  private def seeded(store: InventoryStore[IO]): IO[UUID] = {
    val orderId = UUID.randomUUID()
    val sku = s"sku-${UUID.randomUUID()}"
    for {
      _ <- store.create(NewStock(sku, 10))
      _ <- store.reserveOrder(
        ReserveOrder(
          orderId,
          NonEmptyList.of(
            ReservationLine(
              UUID.randomUUID(),
              sku,
              1
            )
          )
        )
      )
    } yield orderId
  }

  private def get(routes: HttpRoutes[IO], path: String) =
    routes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(path)))

  private def resolve(
      routes: HttpRoutes[IO],
      orderId: UUID,
      token: Option[String],
      body: ResolveReconciliationRequest
  ) = {
    val request = Request[IO](
      Method.PATCH,
      Uri.unsafeFromString(s"/reconciliations/$orderId")
    ).withEntity(body)
    routes.orNotFound.run(
      token.fold(request)(value => request.putHeaders("Authorization" -> value))
    )
  }

  test("GET /reconciliations lists and filters the queue") {
    for {
      store <- InventoryStore.inMemory[IO]
      orderId <- seeded(store)
      _ <- store.flagReconciliation(orderId, "cancel_after_dispatch")
      response <- get(routes(store), "/reconciliations?status=open")
      body <- response.as[List[ReconciliationResponse]]
      invalid <- get(routes(store), "/reconciliations?status=bogus")
      malformed <- get(routes(store), "/reconciliations?orderId=not-a-uuid")
      malformedBody <- malformed.as[List[ReconciliationResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(body.map(_.reason), List("cancel_after_dispatch"))
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(malformed.status, Status.Ok)
      assertEquals(malformedBody, Nil)
    }
  }

  test("PATCH /reconciliations requires the operator token") {
    for {
      store <- InventoryStore.inMemory[IO]
      orderId <- seeded(store)
      _ <- store.flagReconciliation(orderId, "cancel_after_dispatch")
      noToken <- resolve(
        routes(store),
        orderId,
        None,
        ResolveReconciliationRequest("resolved")
      )
      wrong <- resolve(
        routes(store),
        orderId,
        Some("Bearer wrong"),
        ResolveReconciliationRequest("resolved")
      )
    } yield {
      assertEquals(noToken.status, Status.Unauthorized)
      assertEquals(wrong.status, Status.Unauthorized)
    }
  }

  test("PATCH /reconciliations resolves once and is idempotent") {
    for {
      store <- InventoryStore.inMemory[IO]
      orderId <- seeded(store)
      _ <- store.flagReconciliation(orderId, "cancel_after_dispatch")
      first <- resolve(
        routes(store),
        orderId,
        Some("Bearer test-token"),
        ResolveReconciliationRequest("resolved", Some("stock checked"))
      )
      firstBody <- first.as[ReconciliationResponse]
      second <- resolve(
        routes(store),
        orderId,
        Some("Bearer test-token"),
        ResolveReconciliationRequest("resolved", Some("later"))
      )
      secondBody <- second.as[ReconciliationResponse]
      stored <- store.listReconciliations(
        Some(ReconciliationStatus.Resolved),
        Some(orderId)
      )
    } yield {
      assertEquals(first.status, Status.Ok)
      assertEquals(firstBody.operatorId, Some("op-1"))
      assertEquals(firstBody.note, Some("stock checked"))
      assertEquals(second.status, Status.Ok)
      assertEquals(secondBody.note, Some("stock checked"))
      assertEquals(stored.map(_.operatorId), List(Some("op-1")))
    }
  }

  test("PATCH /reconciliations validates status and unknown flags") {
    for {
      store <- InventoryStore.inMemory[IO]
      orderId <- seeded(store)
      _ <- store.flagReconciliation(orderId, "cancel_after_dispatch")
      invalid <- resolve(
        routes(store),
        orderId,
        Some("Bearer test-token"),
        ResolveReconciliationRequest("open")
      )
      missing <- resolve(
        routes(store),
        UUID.randomUUID(),
        Some("Bearer test-token"),
        ResolveReconciliationRequest("resolved")
      )
    } yield {
      assertEquals(invalid.status, Status.BadRequest)
      assertEquals(missing.status, Status.NotFound)
    }
  }
}
