package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import purerest.docs.Docs

class InventoryDocsSuite extends CatsEffectSuite {

  test(
    "the tapir-described endpoint is served and documented via purerest.docs"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      endpoint = InventoryRoutes.serverEndpoint[IO](store, NoOpLogger[IO])
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
}
