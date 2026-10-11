package inventoryservice

import cats.Applicative
import cats.effect.{Async, Resource, Sync}
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.kafka._
import io.circe.syntax._

import java.util.UUID
import scala.concurrent.duration._

trait StockEventPublisher[F[_]] {
  def publishReserved(event: StockReservedEvent): F[Unit]
  def publishFailed(event: StockReservationFailedEvent): F[Unit]
}

object StockEventPublisher {

  val topic: String = "inventory-events"
  private val source: String = "inventory-service"

  /** Bounds how long a single publish call can take - a slow/unreachable broker
    * must never hang the sync reserve-stock HTTP response indefinitely. The
    * caller (`InventoryRoutes`) is responsible for catching/logging a failure
    * here; this publisher never swallows one itself, so callers can tell a real
    * failure apart from success.
    */
  private val publishTimeout: FiniteDuration = 2.seconds

  /** For call sites that don't care about Kafka at all (most of
    * `InventoryRoutesSuite`'s tests) - never produces anything.
    */
  def noOp[F[_]: Applicative]: StockEventPublisher[F] =
    new StockEventPublisher[F] {
      def publishReserved(event: StockReservedEvent): F[Unit] =
        Applicative[F].unit
      def publishFailed(event: StockReservationFailedEvent): F[Unit] =
        Applicative[F].unit
    }

  def resource[F[_]: Async](
      config: KafkaConfig
  ): Resource[F, StockEventPublisher[F]] = {
    val producerSettings =
      ProducerSettings[F, String, String]
        .withBootstrapServers(config.bootstrapServers)

    KafkaProducer.resource(producerSettings).map { producer =>
      new StockEventPublisher[F] {
        private def publish(key: String, envelope: EventEnvelope): F[Unit] =
          producer
            .produceOne_(ProducerRecord(topic, key, envelope.asJson.noSpaces))
            .flatten
            .timeout(publishTimeout)
            .void

        private def envelopeFor[A: io.circe.Encoder](
            eventType: String,
            correlationId: String,
            timestamp: java.time.Instant,
            payload: A
        ): F[EventEnvelope] =
          Sync[F].delay(UUID.randomUUID().toString).map { eventId =>
            EventEnvelope(
              eventId = eventId,
              eventType = eventType,
              timestamp = timestamp,
              source = source,
              correlationId = correlationId,
              payload = payload.asJson
            )
          }

        def publishReserved(event: StockReservedEvent): F[Unit] =
          envelopeFor(
            "StockReserved",
            event.orderItemId,
            event.timestamp,
            event
          ).flatMap(publish(event.sku, _))

        def publishFailed(event: StockReservationFailedEvent): F[Unit] =
          envelopeFor(
            "StockReservationFailed",
            event.orderItemId,
            event.timestamp,
            event
          ).flatMap(publish(event.sku, _))
      }
    }
  }
}
