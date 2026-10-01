package inventoryservice

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.{
  ERROR,
  INFO,
  WARN
}
import purerest.tracing.{ServerTracing, Tracing}

class InventoryRoutesSuite extends CatsEffectSuite {

  private def failingStore(error: Throwable): InventoryStore[IO] =
    new InventoryStore[IO] {
      def create(sku: String, quantityAvailable: Int): IO[Inventory] =
        IO.raiseError(error)
      def get(id: String): IO[Option[Inventory]] = IO.pure(None)
      def update(
          id: String,
          quantityAvailable: Int,
          quantityReserved: Int
      ): IO[Option[Inventory]] =
        IO.raiseError(error)
      def delete(id: String): IO[Boolean] = IO.raiseError(error)
      def reserve(
          sku: String,
          quantity: Int
      ): IO[Either[InventoryError, Inventory]] = IO.raiseError(error)
      def ping: IO[Boolean] = IO.raiseError(error)
    }

  /** Records every publish call it receives, so tests can assert exactly what
    * was (or wasn't) published without touching a real Kafka broker.
    */
  private def recordingPublisher(): IO[
    (
        StockEventPublisher[IO],
        IO[List[StockReservedEvent]],
        IO[List[StockReservationFailedEvent]]
    )
  ] =
    for {
      reserved <- Ref.of[IO, List[StockReservedEvent]](Nil)
      failed <- Ref.of[IO, List[StockReservationFailedEvent]](Nil)
    } yield {
      val publisher = new StockEventPublisher[IO] {
        def publishReserved(event: StockReservedEvent): IO[Unit] =
          reserved.update(event :: _)
        def publishFailed(event: StockReservationFailedEvent): IO[Unit] =
          failed.update(event :: _)
      }
      (publisher, reserved.get, failed.get)
    }

  test("POST /inventorys returns 201 with the created entity") {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      request = Request[IO](Method.POST, uri"/inventorys")
        .withEntity(CreateInventoryRequest("sku-widget-1", 100))
      response <- routes.orNotFound.run(request)
      entity <- response.as[InventoryResponse]
    } yield {
      assertEquals(response.status, Status.Created)
      assert(entity.id.nonEmpty)
    }
  }

  test("GET /inventorys/{id} returns 200 with the persisted entity") {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      created <- postResponse.as[InventoryResponse]
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/inventorys" / created.id)
      )
      fetched <- getResponse.as[InventoryResponse]
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      assertEquals(fetched, created)
    }
  }

  test(
    "GET /inventorys/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/inventorys" / "unknown-id")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "POST /inventorys logs a received-request line and a completed line with structured context"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = InventoryRoutes.routes[IO](store, testLogger)
      request = Request[IO](Method.POST, uri"/inventorys")
        .withEntity(CreateInventoryRequest("sku-widget-1", 100))
      response <- routes.orNotFound.run(request)
      entity <- response.as[InventoryResponse]
      logged <- testLogger.logged
    } yield {
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("POST")
        ),
        s"expected a received-request INFO line with method context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("inventory_id").contains(entity.id)
        ),
        s"expected a completed INFO line with inventory_id context, got: $infos"
      )
    }
  }

  test(
    "POST /inventorys logs an ERROR with the raised throwable when persisting the entity fails"
  ) {
    val boom = new RuntimeException("boom")
    for {
      testLogger <- IO.pure(StructuredTestingLogger.impl[IO]())
      routes = InventoryRoutes.routes[IO](failingStore(boom), testLogger)
      request = Request[IO](Method.POST, uri"/inventorys")
        .withEntity(CreateInventoryRequest("sku-widget-1", 100))
      response <- routes.orNotFound.run(request)
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.InternalServerError)
      val errors = logged.collect { case m: ERROR => m }
      assert(
        errors.exists(m => m.throwOpt.contains(boom)),
        s"expected an ERROR line with the raised throwable, got: $errors"
      )
    }
  }

  test(
    "GET /inventorys/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = InventoryRoutes.routes[IO](store, testLogger)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      created <- postResponse.as[InventoryResponse]
      _ <- testLogger.logged // drain POST's own log lines before the GET
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/inventorys" / created.id)
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(getResponse.status, Status.Ok)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("GET") &&
            m.ctx.get("inventory_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("inventory_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test(
    "GET /inventorys/{id} logs a WARN for an unknown id"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = InventoryRoutes.routes[IO](store, testLogger)
      response <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/inventorys" / "unknown-id")
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("inventory_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test("PATCH /inventorys/{id} returns 200 with the updated entity") {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      created <- postResponse.as[InventoryResponse]
      patchResponse <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/inventorys" / created.id)
          .withEntity(UpdateInventoryRequest(100, 0))
      )
      updated <- patchResponse.as[InventoryResponse]
    } yield {
      assertEquals(patchResponse.status, Status.Ok)
      assertEquals(updated.id, created.id)
    }
  }

  test(
    "PATCH /inventorys/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/inventorys" / "unknown-id")
          .withEntity(UpdateInventoryRequest(100, 0))
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "PATCH /inventorys/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = InventoryRoutes.routes[IO](store, testLogger)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      created <- postResponse.as[InventoryResponse]
      _ <- testLogger.logged // drain POST's own log lines before the PATCH
      patchResponse <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/inventorys" / created.id)
          .withEntity(UpdateInventoryRequest(100, 0))
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(patchResponse.status, Status.Ok)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("PATCH") &&
            m.ctx.get("inventory_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("inventory_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test("PATCH /inventorys/{id} logs a WARN for an unknown id") {
    for {
      store <- InventoryStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = InventoryRoutes.routes[IO](store, testLogger)
      response <- routes.orNotFound.run(
        Request[IO](Method.PATCH, uri"/inventorys" / "unknown-id")
          .withEntity(UpdateInventoryRequest(100, 0))
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("inventory_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test(
    "DELETE /inventorys/{id} returns 204, and a subsequent GET returns 404"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      created <- postResponse.as[InventoryResponse]
      deleteResponse <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/inventorys" / created.id)
      )
      getResponse <- routes.orNotFound.run(
        Request[IO](Method.GET, uri"/inventorys" / created.id)
      )
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      assertEquals(getResponse.status, Status.NotFound)
    }
  }

  test(
    "DELETE /inventorys/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/inventorys" / "unknown-id")
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "DELETE /inventorys/{id} logs a received-request line and a completed line for a found entity"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = InventoryRoutes.routes[IO](store, testLogger)
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      created <- postResponse.as[InventoryResponse]
      _ <- testLogger.logged // drain POST's own log lines before the DELETE
      deleteResponse <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/inventorys" / created.id)
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(deleteResponse.status, Status.NoContent)
      val infos = logged.collect { case m: INFO => m }
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("received") &&
            m.ctx.get("method").contains("DELETE") &&
            m.ctx.get("inventory_id").contains(created.id)
        ),
        s"expected a received-request INFO line with method/id context, got: $infos"
      )
      assert(
        infos.exists(m =>
          m.message.toLowerCase.contains("completed") &&
            m.ctx.get("inventory_id").contains(created.id)
        ),
        s"expected a completed INFO line with id context, got: $infos"
      )
    }
  }

  test("DELETE /inventorys/{id} logs a WARN for an unknown id") {
    for {
      store <- InventoryStore.inMemory[IO]
      testLogger = StructuredTestingLogger.impl[IO]()
      routes = InventoryRoutes.routes[IO](store, testLogger)
      response <- routes.orNotFound.run(
        Request[IO](Method.DELETE, uri"/inventorys" / "unknown-id")
      )
      logged <- testLogger.logged
    } yield {
      assertEquals(response.status, Status.NotFound)
      val warns = logged.collect { case m: WARN => m }
      assert(
        warns.exists(m =>
          m.message.toLowerCase.contains("not found") &&
            m.ctx.get("inventory_id").contains("unknown-id")
        ),
        s"expected a 'not found' WARN line with id context, got: $warns"
      )
    }
  }

  test("PUT /inventorys/{id} returns 200 with the replaced entity") {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      created <- postResponse.as[InventoryResponse]
      putResponse <- routes.orNotFound.run(
        Request[IO](Method.PUT, uri"/inventorys" / created.id)
          .withEntity(UpdateInventoryRequest(100, 0))
      )
      replaced <- putResponse.as[InventoryResponse]
    } yield {
      assertEquals(putResponse.status, Status.Ok)
      assertEquals(replaced.id, created.id)
    }
  }

  test(
    "PUT /inventorys/{id} returns 404 with a JSON error body for an unknown id"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      response <- routes.orNotFound.run(
        Request[IO](Method.PUT, uri"/inventorys" / "unknown-id")
          .withEntity(UpdateInventoryRequest(100, 0))
      )
      body <- response.as[io.circe.Json]
    } yield {
      assertEquals(response.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "POST /inventorys/reservations returns 200 with the updated entity on success"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      postResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      created <- postResponse.as[InventoryResponse]
      reserveResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys/reservations")
          .withEntity(ReserveInventoryRequest("sku-widget-1", 30))
      )
      reserved <- reserveResponse.as[InventoryResponse]
    } yield {
      assertEquals(reserveResponse.status, Status.Ok)
      assertEquals(reserved.id, created.id)
      assertEquals(reserved.quantityAvailable, 70)
      assertEquals(reserved.quantityReserved, 30)
    }
  }

  test(
    "POST /inventorys/reservations returns 409 with a JSON error body when quantity exceeds available"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      _ <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 10)
        )
      )
      reserveResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys/reservations")
          .withEntity(ReserveInventoryRequest("sku-widget-1", 20))
      )
      body <- reserveResponse.as[io.circe.Json]
    } yield {
      assertEquals(reserveResponse.status, Status.Conflict)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "POST /inventorys/reservations returns 404 with a JSON error body for an unknown sku"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      reserveResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys/reservations")
          .withEntity(ReserveInventoryRequest("unknown-sku", 10))
      )
      body <- reserveResponse.as[io.circe.Json]
    } yield {
      assertEquals(reserveResponse.status, Status.NotFound)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "POST /inventorys/reservations returns 400 with a JSON error body for a zero or negative quantity"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO])
      _ <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      reserveResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys/reservations")
          .withEntity(ReserveInventoryRequest("sku-widget-1", 0))
      )
      body <- reserveResponse.as[io.circe.Json]
    } yield {
      assertEquals(reserveResponse.status, Status.BadRequest)
      assert(
        body.asObject.exists(_.contains("error")),
        s"expected a JSON error body, got: $body"
      )
    }
  }

  test(
    "POST /inventorys/reservations publishes inventory.stock-reserved on success"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      publisherInfo <- recordingPublisher()
      (publisher, reservedEvents, failedEvents) = publisherInfo
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], publisher)
      _ <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      reserveResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys/reservations")
          .withEntity(ReserveInventoryRequest("sku-widget-1", 30))
      )
      reserved <- reservedEvents
      failed <- failedEvents
    } yield {
      assertEquals(reserveResponse.status, Status.Ok)
      assertEquals(
        reserved.map(e => (e.sku, e.quantity)),
        List(("sku-widget-1", 30))
      )
      assertEquals(failed, Nil)
    }
  }

  test(
    "POST /inventorys/reservations publishes inventory.stock-reservation-failed on insufficient stock"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      publisherInfo <- recordingPublisher()
      (publisher, reservedEvents, failedEvents) = publisherInfo
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], publisher)
      _ <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 10)
        )
      )
      reserveResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys/reservations")
          .withEntity(ReserveInventoryRequest("sku-widget-1", 20))
      )
      reserved <- reservedEvents
      failed <- failedEvents
    } yield {
      assertEquals(reserveResponse.status, Status.Conflict)
      assertEquals(reserved, Nil)
      assertEquals(
        failed.map(e => (e.sku, e.quantity)),
        List(("sku-widget-1", 20))
      )
    }
  }

  test(
    "POST /inventorys/reservations never publishes for an unknown sku or an invalid quantity"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      publisherInfo <- recordingPublisher()
      (publisher, reservedEvents, failedEvents) = publisherInfo
      routes = InventoryRoutes.routes[IO](store, NoOpLogger[IO], publisher)
      _ <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys").withEntity(
          CreateInventoryRequest("sku-widget-1", 100)
        )
      )
      notFoundResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys/reservations")
          .withEntity(ReserveInventoryRequest("unknown-sku", 10))
      )
      invalidQuantityResponse <- routes.orNotFound.run(
        Request[IO](Method.POST, uri"/inventorys/reservations")
          .withEntity(ReserveInventoryRequest("sku-widget-1", 0))
      )
      reserved <- reservedEvents
      failed <- failedEvents
    } yield {
      assertEquals(notFoundResponse.status, Status.NotFound)
      assertEquals(invalidQuantityResponse.status, Status.BadRequest)
      assertEquals(reserved, Nil)
      assertEquals(failed, Nil)
    }
  }

  test(
    "wrapped routes (with tracing middleware) record a span for a handled request"
  ) {
    Tracing.test[IO]("inventory-service-test").use { testTracer =>
      for {
        store <- InventoryStore.inMemory[IO]
        routes = ServerTracing.middleware(testTracer.tracer)(
          InventoryRoutes.routes[IO](store, NoOpLogger[IO])
        )
        request = Request[IO](Method.POST, uri"/inventorys")
          .withEntity(CreateInventoryRequest("sku-widget-1", 100))
        response <- routes.orNotFound.run(request)
        spans <- testTracer.finishedSpans
      } yield {
        assertEquals(response.status, Status.Created)
        assertEquals(spans.map(_.getName), List("POST /inventorys"))
      }
    }
  }
}
