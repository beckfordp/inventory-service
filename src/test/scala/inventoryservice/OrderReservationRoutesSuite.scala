// SPDX-License-Identifier: MIT

package inventoryservice

import inventoryservice.domain._
import cats.effect.IO
import io.circe.Json
import cats.syntax.all._
import io.circe.syntax._
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{HttpRoutes, Method, Request, Status, Uri}
import org.typelevel.log4cats.noop.NoOpLogger

import java.util.UUID

class OrderReservationRoutesSuite extends CatsEffectSuite {

  private def setup(stock: (String, Int)*): IO[HttpRoutes[IO]] =
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- stock.toList.traverse_ { case (sku, qty) => store.seed(sku, qty) }
    } yield InventoryRoutes.routes[IO](store, NoOpLogger[IO])

  private def reserve(routes: HttpRoutes[IO], body: Json) =
    routes.orNotFound.run(
      Request[IO](Method.POST, uri"/inventorys/order-reservations")
        .withEntity(body)
    )

  private def line(id: String, sku: String, qty: Int): Json =
    Json.obj(
      "orderItemId" -> id.asJson,
      "sku" -> sku.asJson,
      "quantity" -> qty.asJson
    )

  private def failure(id: String, sku: String, reason: String): Json =
    Json.obj(
      "orderItemId" -> id.asJson,
      "sku" -> sku.asJson,
      "reason" -> reason.asJson
    )

  private def body(orderId: String, items: (String, String, Int)*): Json =
    Json.obj(
      "orderId" -> Json.fromString(orderId),
      "items" -> Json.arr(items.map { case (id, sku, qty) =>
        line(id, sku, qty)
      }: _*)
    )

  private val orderId = UUID.randomUUID().toString
  private val (itemA, itemB) =
    (UUID.randomUUID().toString, UUID.randomUUID().toString)

  test(
    "POST /inventorys/order-reservations returns 201 with the reserved lines"
  ) {
    for {
      routes <- setup("sku-a" -> 5, "sku-b" -> 5)
      response <- reserve(
        routes,
        body(orderId, (itemA, "sku-a", 2), (itemB, "sku-b", 1))
      )
      json <- response.as[Json]
    } yield {
      assertEquals(response.status, Status.Created)
      assertEquals(
        json,
        Json.obj(
          "orderId" -> orderId.asJson,
          "items" -> Json.arr(line(itemA, "sku-a", 2), line(itemB, "sku-b", 1))
        )
      )
    }
  }

  test(
    "POST /inventorys/order-reservations returns 409 listing every failed line"
  ) {
    for {
      routes <- setup("sku-a" -> 1)
      response <- reserve(
        routes,
        body(orderId, (itemA, "sku-a", 2), (itemB, "sku-x", 1))
      )
      json <- response.as[Json]
    } yield {
      assertEquals(response.status, Status.Conflict)
      assertEquals(
        json,
        Json.obj(
          "error" -> "Insufficient stock".asJson,
          "failures" -> Json.arr(
            failure(itemA, "sku-a", "insufficient_stock"),
            failure(itemB, "sku-x", "unknown_sku")
          )
        )
      )
    }
  }

  test(
    "POST /inventorys/order-reservations repeated for an orderId returns 201 with the original lines"
  ) {
    for {
      routes <- setup("sku-a" -> 5)
      first <- reserve(routes, body(orderId, (itemA, "sku-a", 2)))
        .flatMap(_.as[Json])
      again <- reserve(routes, body(orderId, (itemA, "sku-a", 2)))
      againJson <- again.as[Json]
      listed <- routes.orNotFound
        .run(Request[IO](Method.GET, uri"/inventorys?sku=sku-a"))
        .flatMap(_.as[List[InventoryResponse]])
    } yield {
      assertEquals(again.status, Status.Created)
      assertEquals(againJson, first)
      assertEquals(listed.map(_.quantityAvailable), List(3))
    }
  }

  List(
    "empty items" -> (body(orderId), "items must not be empty"),
    "non-UUID orderId" -> (
      body("nope", (itemA, "sku-a", 1)),
      "orderId must be a UUID"
    ),
    "non-UUID orderItemId" -> (
      body(orderId, ("nope", "sku-a", 1)),
      "orderItemId must be a UUID"
    ),
    "non-positive quantity" -> (
      body(orderId, (itemA, "sku-a", 0)),
      "quantity must be positive"
    ),
    "duplicate orderItemId" -> (
      body(orderId, (itemA, "sku-a", 1), (itemA, "sku-a", 1)),
      "duplicate orderItemId"
    )
  ).foreach { case (label, (request, message)) =>
    test(s"POST /inventorys/order-reservations returns 400 for $label") {
      for {
        routes <- setup("sku-a" -> 5)
        response <- reserve(routes, request)
        json <- response.as[Json]
      } yield {
        assertEquals(response.status, Status.BadRequest)
        assertEquals(json, Json.obj("error" -> message.asJson))
      }
    }
  }

  private def release(routes: HttpRoutes[IO], id: String) =
    routes.orNotFound.run(
      Request[IO](
        Method.POST,
        uri"/inventorys/order-reservations" / id / "release"
      )
    )

  private def available(routes: HttpRoutes[IO], sku: String) =
    routes.orNotFound
      .run(
        Request[IO](Method.GET, uri"/inventorys".withQueryParam("sku", sku))
      )
      .flatMap(_.as[List[InventoryResponse]])
      .map(_.map(i => (i.quantityAvailable, i.quantityReserved)))

  test(
    "POST .../{orderId}/release returns 204 and restores stock exactly once"
  ) {
    for {
      routes <- setup("sku-a" -> 5)
      _ <- reserve(routes, body(orderId, (itemA, "sku-a", 2)))
      first <- release(routes, orderId)
      afterFirst <- available(routes, "sku-a")
      second <- release(routes, orderId)
      afterSecond <- available(routes, "sku-a")
    } yield {
      assertEquals(first.status, Status.NoContent)
      assertEquals(second.status, Status.NoContent)
      assertEquals(afterFirst, List((5, 0)))
      assertEquals(afterSecond, List((5, 0)))
    }
  }

  test("POST .../{orderId}/release returns 204 for an unknown orderId") {
    setup().flatMap(release(_, UUID.randomUUID().toString)).map { response =>
      assertEquals(response.status, Status.NoContent)
    }
  }

  test("POST .../{orderId}/release returns 204 for a non-UUID orderId") {
    setup().flatMap(release(_, "not-a-uuid")).map { response =>
      assertEquals(response.status, Status.NoContent)
    }
  }

  test(
    "POST /inventorys/order-reservations after release returns 409 already released or shipped"
  ) {
    for {
      routes <- setup("sku-a" -> 5)
      _ <- reserve(routes, body(orderId, (itemA, "sku-a", 2)))
      _ <- release(routes, orderId)
      response <- reserve(routes, body(orderId, (itemA, "sku-a", 2)))
      json <- response.as[Json]
    } yield {
      assertEquals(response.status, Status.Conflict)
      assertEquals(
        json,
        Json.obj(
          "error" -> "Order reservation already released or shipped".asJson
        )
      )
    }
  }

  test(
    "GET /inventorys/order-reservations returns the ledger filtered by order"
  ) {
    for {
      routes <- setup("sku-a" -> 5)
      _ <- reserve(routes, body(orderId, (itemA, "sku-a", 1)))
      response <- routes.orNotFound.run(
        Request[IO](
          Method.GET,
          Uri.unsafeFromString(
            s"/inventorys/order-reservations?orderId=$orderId"
          )
        )
      )
      entries <- response.as[List[ReservationLedgerEntryResponse]]
      releasedResponse <- routes.orNotFound.run(
        Request[IO](
          Method.GET,
          Uri.unsafeFromString(
            s"/inventorys/order-reservations?orderId=$orderId&status=released"
          )
        )
      )
      released <- releasedResponse.as[List[ReservationLedgerEntryResponse]]
    } yield {
      assertEquals(response.status, Status.Ok)
      assertEquals(entries.map(_.status), List("active"))
      assertEquals(entries.map(_.lineNo), List(0))
      assertEquals(entries.map(_.quantity), List(1))
      assertEquals(released, Nil)
    }
  }

  test("GET /inventorys/order-reservations rejects invalid filters") {
    for {
      routes <- setup("sku-a" -> 5)
      badOrder <- routes.orNotFound.run(
        Request[IO](
          Method.GET,
          uri"/inventorys/order-reservations?orderId=not-a-uuid"
        )
      )
      badStatus <- routes.orNotFound.run(
        Request[IO](
          Method.GET,
          uri"/inventorys/order-reservations?status=gone"
        )
      )
    } yield {
      assertEquals(badOrder.status, Status.BadRequest)
      assertEquals(badStatus.status, Status.BadRequest)
    }
  }

  test("the 409 body decodes back to ReservationRejected for tapir clients") {
    val id = UUID.randomUUID()
    assertEquals(
      OrderReservationRoutes.fromRejectedResponse(
        ReservationRejectedResponse(
          "Insufficient stock",
          List(ReservationFailureResponse(id.toString, "sku-a", "unknown_sku"))
        )
      ),
      sttp.tapir.DecodeResult.Value(
        ReservationRejected(
          cats.data.NonEmptyList.of(
            ReservationFailure(id, "sku-a", FailureReason.UnknownSku)
          )
        )
      )
    )
    assert(
      OrderReservationRoutes
        .fromRejectedResponse(ReservationRejectedResponse("x", Nil))
        .isInstanceOf[sttp.tapir.DecodeResult.Error]
    )
  }
}
