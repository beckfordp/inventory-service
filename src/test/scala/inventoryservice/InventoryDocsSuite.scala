package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs
import sttp.tapir.EndpointOutput
import sttp.tapir.server.ServerEndpoint

class InventoryDocsSuite extends CatsEffectSuite {

  test(
    "the tapir-described endpoint is served and documented via purerest.docs"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      endpoint = InventoryRoutes
        .createInventoryServerEndpoint[IO](store, NoOpLogger[IO])
      routes = Docs.routes[IO]("Inventory Service", "1.0", List(endpoint))
      request = Request[IO](Method.POST, uri"/inventorys")
        .withEntity(CreateInventoryRequest("sku-widget-1", 100))
      response <- routes.orNotFound.run(request)
      entity <- response.as[InventoryResponse]
      docsResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/docs.yaml")
      )
      docsBody <- docsResponse.bodyText.compile.string
    } yield {
      assertEquals(response.status, Status.Created)
      assert(entity.id.nonEmpty)
      assertEquals(docsResponse.status, Status.Ok)
      assert(clue(docsBody).contains("/inventorys"))
    }
  }

  test("the order-reservation endpoints are documented") {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = Docs.routes[IO](
        "Inventory Service",
        "1.0",
        OrderReservationRoutes.serverEndpoints[IO](store, NoOpLogger[IO])
      )
      docsBody <- routes.orNotFound
        .run(Request[IO](Method.GET, uri"/docs/docs.yaml"))
        .flatMap(_.bodyText.compile.string)
    } yield {
      assert(clue(docsBody).contains("/inventorys/order-reservations:"))
      assert(clue(docsBody).contains("'409':"))
      assert(
        clue(docsBody).contains(
          "/inventorys/order-reservations/{orderId}/release:"
        )
      )
    }
  }

  /** Every fixed status an output can produce (tapir's own traversal is
    * internal).
    */
  private def statuses(output: EndpointOutput[?]): List[Int] =
    output match {
      case EndpointOutput.FixedStatusCode(code, _, _) => List(code.code)
      case EndpointOutput.OneOf(variants, _)          =>
        variants.flatMap(v => statuses(v.output))
      case EndpointOutput.Pair(left, right, _, _) =>
        statuses(left) ++ statuses(right)
      case EndpointOutput.MappedPair(pair, _) => statuses(pair)
      case _                                  => Nil
    }

  private def errorStatuses(endpoint: ServerEndpoint[Any, IO]): List[Int] =
    statuses(endpoint.endpoint.errorOutput).sorted

  test("each endpoint documents only the errors it can return") {
    for {
      store <- InventoryStore.inMemory[IO]
    } yield {
      val logger = NoOpLogger[IO]
      assertEquals(
        errorStatuses(
          InventoryRoutes.createInventoryServerEndpoint[IO](store, logger)
        ),
        List(400, 409)
      )
      assertEquals(
        errorStatuses(
          InventoryRoutes.getInventoryServerEndpoint[IO](store, logger)
        ),
        List(404)
      )
      assertEquals(
        errorStatuses(
          InventoryRoutes.updateInventoryServerEndpoint[IO](store, logger)
        ),
        List(400, 404)
      )
      assertEquals(
        errorStatuses(
          InventoryRoutes.replaceInventoryServerEndpoint[IO](store, logger)
        ),
        List(400, 404)
      )
      assertEquals(
        errorStatuses(
          InventoryRoutes.deleteInventoryServerEndpoint[IO](store, logger)
        ),
        List(404)
      )
      assertEquals(
        errorStatuses(
          InventoryRoutes.listInventoryServerEndpoint[IO](store, logger)
        ),
        Nil
      )
      assertEquals(
        errorStatuses(
          OrderReservationRoutes.reserveOrderServerEndpoint[IO](store, logger)
        ),
        List(400, 409, 409)
      )
      assertEquals(
        errorStatuses(
          OrderReservationRoutes.releaseOrderServerEndpoint[IO](store, logger)
        ),
        Nil
      )
      assertEquals(errorStatuses(HealthRoutes.healthServerEndpoint[IO]), Nil)
      assertEquals(
        errorStatuses(HealthRoutes.readyServerEndpoint[IO](store)),
        List(503)
      )
    }
  }

  test("every endpoint is documented") {
    for {
      store <- InventoryStore.inMemory[IO]
    } yield {
      val endpoints = AppRoutes.endpoints[IO](store, NoOpLogger[IO])
      assertEquals(
        Docs.undocumented(endpoints.map(_.endpoint)).map(_.render),
        Nil
      )
    }
  }
}
