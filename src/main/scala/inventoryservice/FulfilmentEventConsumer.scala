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

/** Local mirror of fulfilment-service's `DispatchConfirmed` payload. Only the
  * order id is needed; producer extras (`items`) are ignored by circe.
  */
@description(
  "Order dispatched: pick/pack/dispatch succeeded. Inventory marks the order's active reservation lines shipped."
)
final case class DispatchConfirmedEvent(
    @description("Order UUID (order-service's order id).")
    @encodedExample("3f2b8c1e-5d4a-4e7b-9c6f-1a2b3c4d5e6f")
    orderId: String,
    @description("Carrier tracking number.")
    @encodedExample("TRK-123456789")
    trackingNo: String
)

object DispatchConfirmedEvent {
  implicit val codec: Codec[DispatchConfirmedEvent] = deriveCodec
  implicit val schema: Schema[DispatchConfirmedEvent] = Schema.derived
}

/** Consumes `fulfilment-events: DispatchConfirmed` (US-21.4) and marks the
  * order's active reservation lines `shipped`; a dispatch that finds released
  * lines lost the race and records reconciliation.
  */
object FulfilmentEventConsumer {

  val topic: String = "fulfilment-events"

  val groupId: String = "inventory-service-fulfilment-events"

  private def consumerSettings[F[_]: Async](
      config: KafkaConfig
  ): ConsumerSettings[F, Option[String], Option[String]] =
    ConsumerSettings[F, Option[String], Option[String]]
      .withBootstrapServers(config.bootstrapServers)
      .withGroupId(groupId)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

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
          "Received fulfilment-events record with a null value - dead-lettering it"
        ) *> deadLetter.guard(topic, key, None)(
          MonadThrow[F].raiseError(
            new IllegalArgumentException(
              "fulfilment-events record has a null value"
            )
          )
        )
      case Some(raw) =>
        decode[EventEnvelope](raw) match {
          case Left(error) =>
            logger.error(
              Map("topic" -> topic, "bytes" -> raw.length.toString),
              error
            )("Failed to decode fulfilment-events envelope") *>
              deadLetterRaw(raw, error)
          case Right(envelope) =>
            envelope.eventType match {
              case "DispatchConfirmed" =>
                envelope.payload.as[DispatchConfirmedEvent] match {
                  case Left(error) =>
                    logger.error(
                      Map("topic" -> topic, "bytes" -> raw.length.toString),
                      error
                    )("Failed to decode DispatchConfirmed payload") *>
                      deadLetterRaw(raw, error)
                  case Right(event) =>
                    Uuids.parse(event.orderId) match {
                      case None =>
                        deadLetterRaw(
                          raw,
                          new IllegalArgumentException(
                            s"Invalid orderId in DispatchConfirmed payload: ${event.orderId}"
                          )
                        )
                      case Some(orderId) =>
                        deadLetter.guard(topic, key, Some(raw))(
                          store.markShipped(orderId).flatMap { outcome =>
                            val flagged =
                              if (outcome.released.nonEmpty)
                                store
                                  .flagReconciliation(
                                    orderId,
                                    "dispatch_after_release"
                                  )
                                  .void
                              else MonadThrow[F].unit
                            val context = Map(
                              "order_id" -> event.orderId,
                              "tracking_no" -> event.trackingNo,
                              "shipped_lines" -> outcome.shipped.size.toString,
                              "already_shipped_lines" -> outcome.alreadyShipped.size.toString,
                              "released_lines" -> outcome.released.size.toString
                            )
                            flagged *>
                              (if (
                                 outcome.shipped.isEmpty &&
                                 outcome.alreadyShipped.isEmpty &&
                                 outcome.released.isEmpty
                               )
                                 logger.warn(context)(
                                   "DispatchConfirmed for an unknown reservation - skipping"
                                 )
                               else if (outcome.released.nonEmpty)
                                 logger.warn(context)(
                                   "DispatchConfirmed after release - reconciliation flagged"
                                 )
                               else
                                 logger.info(context)(
                                   if (outcome.shipped.nonEmpty)
                                     "Reservation marked shipped"
                                   else
                                     "DispatchConfirmed for an already-shipped reservation - skipping duplicate"
                                 ))
                          }
                        )
                    }
                }
              case other =>
                logger.info(
                  Map("topic" -> topic, "event_type" -> other)
                )("Ignoring unrelated fulfilment-events event type")
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
