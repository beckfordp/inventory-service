package inventoryservice

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import inventoryservice.domain.{ReservationLine, ReserveOrder}
import io.circe.Json
import io.circe.syntax._
import munit.CatsEffectSuite
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.metrics.Meter
import purekafka.consumer.DeadLetter
import purekafka.resilience.{
  CircuitBreakerConfig,
  ResilienceConfig,
  RetryConfig
}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** `FulfilmentEventConsumer.handle` without a broker (US-21.4): shipping,
  * idempotency, cancel-vs-dispatch reconciliation and DLQ routing.
  */
class FulfilmentEventHandlerSuite extends CatsEffectSuite {

  private type Dlq = Ref[IO, List[(String, String, Option[String])]]

  private def deadLetter(
      dlq: Dlq
  ): IO[DeadLetter[IO, String, Option[String]]] = {
    import org.typelevel.otel4s.metrics.Meter.Implicits.noop
    DeadLetter.fromPublish[IO, String, Option[String]](
      RetryConfig(maxRetries = 0, baseDelay = 1.milli),
      ResilienceConfig(
        retry = RetryConfig(maxRetries = 0, baseDelay = 1.milli),
        circuitBreaker =
          CircuitBreakerConfig(failureThreshold = 100, resetTimeout = 1.second)
      ),
      (topic, key, value) => dlq.update(_ :+ (topic, key, value))
    )(NoOpLogger[IO])(summon[Meter[IO]])
  }

  private def envelope(eventType: String, payload: Json): String =
    EventEnvelope(
      eventId = UUID.randomUUID().toString,
      eventType = eventType,
      timestamp = Instant.parse("2026-01-01T00:00:00Z"),
      source = "fulfilment-service",
      correlationId = "order-1",
      payload = payload
    ).asJson.noSpaces

  private def confirmed(orderId: String): String =
    envelope(
      "DispatchConfirmed",
      DispatchConfirmedEvent(orderId, "TRK-1").asJson
    )

  private def reserved(
      store: InventoryStore[IO],
      orderId: UUID
  ): IO[Unit] = {
    val command = ReserveOrder(
      orderId,
      NonEmptyList.of(ReservationLine(UUID.randomUUID(), "sku-1", 2))
    )
    store.reserveOrder(command).flatMap {
      case Right(_) => IO.unit
      case Left(e)  =>
        IO.raiseError(new IllegalStateException(s"reserve failed: $e"))
    }
  }

  private def levels(store: InventoryStore[IO]): IO[(Int, Int)] =
    store.list(Some("sku-1")).map {
      case inventory :: Nil =>
        (inventory.quantityAvailable, inventory.quantityReserved)
      case other => fail(s"expected one sku-1 row, got $other")
    }

  private def handle(
      store: InventoryStore[IO],
      dl: DeadLetter[IO, String, Option[String]]
  )(key: String, raw: String): IO[Unit] =
    FulfilmentEventConsumer.handle(store, NoOpLogger[IO], dl)(key, Some(raw))

  test("DispatchConfirmed marks active lines shipped") {
    val orderId = UUID.randomUUID()
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-1", 10)
      _ <- reserved(store, orderId)
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- handle(store, dl)(orderId.toString, confirmed(orderId.toString))
      after <- levels(store)
      rows <- store.listReservations(Some(orderId), Some("shipped"))
      routed <- dlq.get
    } yield {
      assertEquals(after, (8, 0))
      assertEquals(rows.map(_.status), List("shipped"))
      assertEquals(routed, Nil)
    }
  }

  test("a duplicate DispatchConfirmed changes nothing") {
    val orderId = UUID.randomUUID()
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-1", 10)
      _ <- reserved(store, orderId)
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- handle(store, dl)(orderId.toString, confirmed(orderId.toString))
      _ <- handle(store, dl)(orderId.toString, confirmed(orderId.toString))
      after <- levels(store)
      reason <- store.reconciliationReason(orderId)
    } yield {
      assertEquals(after, (8, 0))
      assertEquals(reason, None)
    }
  }

  test("DispatchConfirmed after release flags reconciliation") {
    val orderId = UUID.randomUUID()
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-1", 10)
      _ <- reserved(store, orderId)
      _ <- store.releaseOrder(orderId)
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- handle(store, dl)(orderId.toString, confirmed(orderId.toString))
      after <- levels(store)
      reason <- store.reconciliationReason(orderId)
      routed <- dlq.get
    } yield {
      assertEquals(after, (10, 0))
      assertEquals(reason, Some("dispatch_after_release"))
      assertEquals(routed, Nil)
    }
  }

  test("a malformed DispatchConfirmed payload is dead-lettered") {
    val orderId = UUID.randomUUID()
    for {
      store <- InventoryStore.inMemory[IO]
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- handle(store, dl)(
        orderId.toString,
        envelope("DispatchConfirmed", Json.obj("trackingNo" -> "TRK".asJson))
      )
      routed <- dlq.get
    } yield assertEquals(routed.map(_._1), List("fulfilment-events-dlq"))
  }

  test("an unrelated fulfilment event type is ignored") {
    val orderId = UUID.randomUUID()
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-1", 10)
      _ <- reserved(store, orderId)
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- handle(store, dl)(
        orderId.toString,
        envelope(
          "DispatchFailed",
          Json.obj("orderId" -> orderId.toString.asJson)
        )
      )
      after <- levels(store)
      routed <- dlq.get
    } yield {
      assertEquals(after, (8, 2))
      assertEquals(routed, Nil)
    }
  }

  test("DispatchConfirmed for an unknown reservation is a warning no-op") {
    val orderId = UUID.randomUUID()
    for {
      store <- InventoryStore.inMemory[IO]
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- handle(store, dl)(orderId.toString, confirmed(orderId.toString))
      reason <- store.reconciliationReason(orderId)
      routed <- dlq.get
    } yield {
      assertEquals(reason, None)
      assertEquals(routed, Nil)
    }
  }
}
