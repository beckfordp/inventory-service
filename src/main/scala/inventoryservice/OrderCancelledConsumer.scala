// SPDX-License-Identifier: MIT

package inventoryservice

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all._
import fs2.Stream
import fs2.kafka._
import inventoryservice.domain.Uuids
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer
import purekafka.consumer.{DeadLetter, EventAge}
import purekafka.tracing.KafkaTracing
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.{description, encodedExample}

import java.time.Instant

/** Shared Kafka envelope (ADR 0009). */
final case class EventEnvelope(
    eventId: String,
    eventType: String,
    timestamp: Instant,
    source: String,
    correlationId: String,
    payload: io.circe.Json
)

object EventEnvelope {
  implicit val codec: Codec[EventEnvelope] = deriveCodec
}

/** Local mirror of order-service's `OrderCancelled` payload (US-9.2). `reason`
  * stays optional so old reason-less messages decode; releasing stock does not
  * depend on it.
  */
@description(
  "Order cancelled: a customer cancel or a consumed PaymentAuthorizationExpired moved the order to cancelled."
)
final case class OrderCancelledEvent(
    @description("Order UUID (order-service's order id).")
    @encodedExample("3f2b8c1e-5d4a-4e7b-9c6f-1a2b3c4d5e6f")
    orderId: String,
    @description("Customer who placed the order.")
    @encodedExample("7c9e6679-7425-40de-944b-e07fc1f90ae7")
    customerId: String,
    @description("When the order was cancelled (ISO-8601 instant).")
    @encodedExample("2026-10-31T10:15:30Z")
    timestamp: Instant,
    @description(
      "Cancellation trigger: customer | auth_expired | stale | operator."
    )
    @encodedExample("customer")
    reason: Option[String] = None
)

object OrderCancelledEvent {
  implicit val codec: Codec[OrderCancelledEvent] = deriveCodec
  implicit val schema: Schema[OrderCancelledEvent] = Schema.derived
}

/** US-9.4: consumes order-service's shared `OrderCancelled` event and releases
  * the order's reservation. `releaseOrder` is already idempotent, so a
  * redelivered event or an unknown/already-released order is a safe no-op.
  * inventory-service publishes nothing in response.
  */
object OrderCancelledConsumer {

  val topic: String = "order-events"

  val groupId: String = "inventory-service-order-events"

  private def consumerSettings[F[_]: Async](
      config: KafkaConfig
  ): ConsumerSettings[F, Option[String], Option[String]] =
    ConsumerSettings[F, Option[String], Option[String]]
      .withBootstrapServers(config.bootstrapServers)
      .withGroupId(groupId)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

  /** One `order-events` record, minus the offset commit - exposed for
    * broker-free handler tests.
    */
  def handle[F[_]: MonadThrow](
      store: InventoryStore[F],
      logger: StructuredLogger[F],
      deadLetter: DeadLetter[F, String, Option[String]]
  )(key: String, value: Option[String]): F[Unit] = {
    def deadLetterRaw(raw: String, error: Throwable): F[Unit] =
      deadLetter.guard(topic, key, Some(raw))(MonadThrow[F].raiseError(error))

    value match {
      case None =>
        logger.error(Map.empty)(
          "Received order-events record with a null value - dead-lettering it"
        ) *> deadLetter.guard(topic, key, None)(
          MonadThrow[F].raiseError(
            new IllegalArgumentException("order-events record has a null value")
          )
        )
      case Some(raw) =>
        decode[EventEnvelope](raw) match {
          case Left(error) =>
            logger.error(
              Map("topic" -> topic, "bytes" -> raw.length.toString),
              error
            )("Failed to decode order-events envelope") *>
              deadLetterRaw(raw, error)
          case Right(envelope) =>
            envelope.eventType match {
              case "OrderCancelled" =>
                envelope.payload.as[OrderCancelledEvent] match {
                  case Left(error) =>
                    logger.error(
                      Map("topic" -> topic, "bytes" -> raw.length.toString),
                      error
                    )("Failed to decode OrderCancelled payload") *>
                      deadLetterRaw(raw, error)
                  case Right(event) =>
                    Uuids.parse(event.orderId) match {
                      case None =>
                        deadLetterRaw(
                          raw,
                          new IllegalArgumentException(
                            s"Invalid orderId in OrderCancelled payload: ${event.orderId}"
                          )
                        )
                      case Some(orderId) =>
                        deadLetter.guard(topic, key, Some(raw))(
                          store.releaseOrder(orderId).flatMap { outcome =>
                            val flagged =
                              if (outcome.shipped.nonEmpty)
                                store
                                  .flagReconciliation(
                                    orderId,
                                    "cancel_after_dispatch"
                                  )
                                  .void
                              else MonadThrow[F].unit
                            flagged *> logger.info(
                              Map(
                                "order_id" -> event.orderId,
                                "reason" -> event.reason.getOrElse("-"),
                                "released_lines" -> outcome.released.size.toString,
                                "shipped_lines" -> outcome.shipped.size.toString
                              )
                            )(
                              if (outcome.shipped.nonEmpty)
                                "OrderCancelled after dispatch - reconciliation flagged"
                              else "Reservation released on order cancellation"
                            )
                          }
                        )
                    }
                }
              case other =>
                logger.info(
                  Map("topic" -> topic, "event_type" -> other)
                )("Ignoring unrelated order-events event type")
            }
        }
    }
  }

  def run[F[_]: Async](
      config: KafkaConfig,
      store: InventoryStore[F],
      logger: StructuredLogger[F],
      deadLetter: DeadLetter[F, String, Option[String]],
      eventAge: EventAge[F],
      tracer: Tracer[F]
  ): Stream[F, Unit] =
    KafkaConsumer
      .stream(consumerSettings[F](config))
      .subscribeTo(topic)
      .records
      .evalTap(committable =>
        committable.record.timestamp.createTime.traverse_(
          eventAge.record(topic, groupId, _)
        )
      )
      .evalMap { committable =>
        KafkaTracing.traced(tracer)(
          committable.record.headers,
          s"$topic process",
          topic
        )(
          handle(store, logger, deadLetter)(
            committable.record.key.getOrElse(""),
            committable.record.value
          )
        ) *> committable.offset.commit
      }
}
