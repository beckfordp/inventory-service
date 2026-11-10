// SPDX-License-Identifier: MIT

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

/** `OrderCancelledConsumer.handle` without a broker (US-9.4): release,
  * idempotency, and DLQ routing.
  */
class OrderCancelledHandlerSuite extends CatsEffectSuite {

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

  private def envelope(orderId: String): String =
    EventEnvelope(
      eventId = UUID.randomUUID().toString,
      eventType = "OrderCancelled",
      timestamp = Instant.parse("2026-01-01T00:00:00Z"),
      source = "order-service",
      correlationId = orderId,
      payload = OrderCancelledEvent(
        orderId,
        "cust-1",
        Instant.parse("2026-01-01T00:00:00Z"),
        Some("customer")
      ).asJson
    ).asJson.noSpaces

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

  test("OrderCancelled releases the order's reservation") {
    val orderId = UUID.randomUUID()
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-1", 10)
      _ <- reserved(store, orderId)
      before <- levels(store)
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- OrderCancelledConsumer.handle(store, NoOpLogger[IO], dl)(
        orderId.toString,
        Some(envelope(orderId.toString))
      )
      after <- levels(store)
      routed <- dlq.get
    } yield {
      assertEquals(before, (8, 2))
      assertEquals(after, (10, 0))
      assertEquals(routed, Nil)
    }
  }

  test("a duplicate OrderCancelled releases nothing further") {
    val orderId = UUID.randomUUID()
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-1", 10)
      _ <- reserved(store, orderId)
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      handle = OrderCancelledConsumer.handle(store, NoOpLogger[IO], dl) _
      _ <- handle(orderId.toString, Some(envelope(orderId.toString)))
      _ <- handle(orderId.toString, Some(envelope(orderId.toString)))
      after <- levels(store)
    } yield assertEquals(after, (10, 0))
  }

  test(
    "OrderCancelled after dispatch flags reconciliation and does not return stock"
  ) {
    val orderId = UUID.randomUUID()
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-1", 10)
      _ <- reserved(store, orderId)
      _ <- store.markShipped(orderId)
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- OrderCancelledConsumer.handle(store, NoOpLogger[IO], dl)(
        orderId.toString,
        Some(envelope(orderId.toString))
      )
      after <- levels(store)
      reason <- store.reconciliationReason(orderId)
      routed <- dlq.get
    } yield {
      assertEquals(after, (8, 0))
      assertEquals(reason, Some("cancel_after_dispatch"))
      assertEquals(routed, Nil)
    }
  }

  test("a malformed OrderCancelled payload is dead-lettered") {
    for {
      store <- InventoryStore.inMemory[IO]
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- OrderCancelledConsumer.handle(store, NoOpLogger[IO], dl)(
        "key",
        Some("""{"not":"an envelope"}""")
      )
      routed <- dlq.get
    } yield assertEquals(routed.map(_._1), List("order-events-dlq"))
  }

  test("an invalid orderId is dead-lettered") {
    for {
      store <- InventoryStore.inMemory[IO]
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      _ <- OrderCancelledConsumer.handle(store, NoOpLogger[IO], dl)(
        "key",
        Some(envelope("not-a-uuid"))
      )
      routed <- dlq.get
    } yield assertEquals(routed.map(_._1), List("order-events-dlq"))
  }

  test("unrelated order-events event types are ignored") {
    for {
      store <- InventoryStore.inMemory[IO]
      dlq <- Ref.of[IO, List[(String, String, Option[String])]](Nil)
      dl <- deadLetter(dlq)
      raw = EventEnvelope(
        eventId = UUID.randomUUID().toString,
        eventType = "OrderConfirmed",
        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
        source = "order-service",
        correlationId = "order-1",
        payload = Json.obj("orderId" -> Json.fromString("order-1"))
      ).asJson.noSpaces
      _ <- OrderCancelledConsumer.handle(store, NoOpLogger[IO], dl)(
        "order-1",
        Some(raw)
      )
      routed <- dlq.get
    } yield assertEquals(routed, Nil)
  }
}
