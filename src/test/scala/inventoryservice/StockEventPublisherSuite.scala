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

  private def consumeOne(config: KafkaConfig, topic: String): IO[String] = {
    val consumerSettings =
      ConsumerSettings[IO, String, String]
        .withBootstrapServers(config.bootstrapServers)
        .withGroupId(s"test-${UUID.randomUUID()}")
        .withAutoOffsetReset(AutoOffsetReset.Earliest)

    KafkaConsumer.resource(consumerSettings).use { consumer =>
      for {
        _ <- consumer.subscribeTo(topic)
        record <- consumer.stream
          .take(1)
          .compile
          .lastOrError
          .timeout(15.seconds)
      } yield record.record.value
    }
  }

  test(
    "publishReserved produces exactly one message on inventory.stock-reserved"
  ) {
    withContainers { kafka =>
      val config = configFor(kafka)
      val event = StockReservedEvent(
        inventoryId = "inv-1",
        sku = "sku-widget-1",
        quantity = 30,
        timestamp = Instant.parse("2026-01-01T00:00:00Z")
      )
      for {
        consumed <- StockEventPublisher
          .resource[IO](config)
          .use(_.publishReserved(event)) *> consumeOne(
          config,
          StockEventPublisher.reservedTopic
        )
      } yield assertEquals(decode[StockReservedEvent](consumed), Right(event))
    }
  }

  test(
    "publishFailed produces exactly one message on inventory.stock-reservation-failed"
  ) {
    withContainers { kafka =>
      val config = configFor(kafka)
      val event = StockReservationFailedEvent(
        inventoryId = "inv-1",
        sku = "sku-widget-1",
        quantity = 80,
        timestamp = Instant.parse("2026-01-01T00:00:00Z")
      )
      for {
        consumed <- StockEventPublisher
          .resource[IO](config)
          .use(_.publishFailed(event)) *> consumeOne(
          config,
          StockEventPublisher.reservationFailedTopic
        )
      } yield assertEquals(
        decode[StockReservationFailedEvent](consumed),
        Right(event)
      )
    }
  }
}
