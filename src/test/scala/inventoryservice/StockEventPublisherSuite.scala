package inventoryservice

import cats.effect.IO
import com.dimafeng.testcontainers.KafkaContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import fs2.kafka._
import io.circe.parser.decode
import munit.CatsEffectSuite

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class StockEventPublisherSuite
    extends CatsEffectSuite
    with TestContainerForAll {

  override val containerDef: KafkaContainer.Def = KafkaContainer.Def()

  private def configFor(kafka: KafkaContainer): KafkaConfig =
    KafkaConfig(bootstrapServers = kafka.bootstrapServers)

  /** All tests now share one `inventory-events` topic (ADR 0009), so a fresh
    * "earliest" consumer can see another test's leftover message first — filter
    * for the envelope matching this test's own `correlationId` rather than just
    * taking the first record.
    */
  private def consumeEnvelope(
      config: KafkaConfig,
      topic: String,
      correlationId: String
  ): IO[String] = {
    val consumerSettings =
      ConsumerSettings[IO, String, String]
        .withBootstrapServers(config.bootstrapServers)
        .withGroupId(s"test-${UUID.randomUUID()}")
        .withAutoOffsetReset(AutoOffsetReset.Earliest)

    KafkaConsumer.resource(consumerSettings).use { consumer =>
      for {
        _ <- consumer.subscribeTo(topic)
        record <- consumer.stream
          .map(_.record.value)
          .filter(raw =>
            decode[EventEnvelope](raw).exists(
              _.correlationId == correlationId
            )
          )
          .take(1)
          .compile
          .lastOrError
          .timeout(15.seconds)
      } yield record
    }
  }

  test(
    "publishReserved produces one message on inventory-events, wrapped in an envelope"
  ) {
    withContainers { kafka =>
      val config = configFor(kafka)
      val event = StockReservedEvent(
        orderItemId = "item-reserved-1",
        sku = "sku-widget-1",
        quantity = 30,
        timestamp = Instant.parse("2026-01-01T00:00:00Z")
      )
      for {
        consumed <- StockEventPublisher
          .resource[IO](config)
          .use(_.publishReserved(event)) *> consumeEnvelope(
          config,
          StockEventPublisher.topic,
          event.orderItemId
        )
        envelope = decode[EventEnvelope](consumed).toOption.get
      } yield {
        assertEquals(envelope.eventType, "StockReserved")
        assertEquals(envelope.source, "inventory-service")
        assertEquals(envelope.correlationId, "item-reserved-1")
        assert(envelope.eventId.nonEmpty)
        assertEquals(envelope.payload.as[StockReservedEvent], Right(event))
      }
    }
  }

  test(
    "publishFailed produces one message on inventory-events, wrapped in an envelope"
  ) {
    withContainers { kafka =>
      val config = configFor(kafka)
      val event = StockReservationFailedEvent(
        orderItemId = "item-failed-1",
        sku = "sku-widget-1",
        quantity = 80,
        timestamp = Instant.parse("2026-01-01T00:00:00Z")
      )
      for {
        consumed <- StockEventPublisher
          .resource[IO](config)
          .use(_.publishFailed(event)) *> consumeEnvelope(
          config,
          StockEventPublisher.topic,
          event.orderItemId
        )
        envelope = decode[EventEnvelope](consumed).toOption.get
      } yield {
        assertEquals(envelope.eventType, "StockReservationFailed")
        assertEquals(envelope.source, "inventory-service")
        assertEquals(envelope.correlationId, "item-failed-1")
        assert(envelope.eventId.nonEmpty)
        assertEquals(
          envelope.payload.as[StockReservationFailedEvent],
          Right(event)
        )
      }
    }
  }
}
