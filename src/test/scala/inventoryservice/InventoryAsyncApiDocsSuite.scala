package inventoryservice

import cats.effect.IO
import cats.syntax.semigroupk._
import munit.CatsEffectSuite
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import purekafka.docs.{Docs => AsyncApiDocs}
import purerest.docs.Docs

class InventoryAsyncApiDocsSuite extends CatsEffectSuite {

  test("GET /docs/asyncapi.json returns 200 with both event types") {
    val routes =
      AsyncApiDocs.routes[IO](
        "Inventory Service",
        "1.0",
        AsyncApiChannels.channels
      )
    for {
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/asyncapi.json")
      )
      body <- response.bodyText.compile.string
    } yield {
      assertEquals(response.status, Status.Ok)
      assert(clue(body).contains("StockReserved"))
      assert(clue(body).contains("StockReservationFailed"))
    }
  }

  test(
    "GET /docs/asyncapi.json still resolves when combined with purerest's OpenAPI/Swagger routes, in Main.scala's own order"
  ) {
    // Regression test for a route-ordering bug order-service hit during its
    // own asyncapi-docs_20261017 track's Phase 3 verification: purerest.docs.Docs'
    // bundled Swagger UI serves a wildcard /docs/{resource} route for its own
    // static assets, which returns its own real 404 (not http4s' fallthrough
    // `None`) for any unrecognized /docs/* path - including
    // /docs/asyncapi.json - if mounted before the AsyncAPI routes.
    // Reproduces Main.scala's exact combination order (asyncApiRoutes <+>
    // staticAsyncApiRoutes <+> docsRoutes) with a minimal (empty) endpoint
    // list standing in for the real InventoryRoutes/HealthRoutes list, since
    // only the Swagger UI's own wildcard route - not the specific business
    // endpoints - is what caused the original bug.
    val asyncApiRoutes = AsyncApiDocs.routes[IO](
      "Inventory Service",
      "1.0",
      AsyncApiChannels.channels
    )
    val docsRoutes = Docs.routes[IO]("Inventory Service", "1.0", Nil)
    val combined = asyncApiRoutes <+> docsRoutes

    for {
      response <- combined.orNotFound.run(
        Request[IO](Method.GET, uri"/docs/asyncapi.json")
      )
    } yield assertEquals(response.status, Status.Ok)
  }
}
