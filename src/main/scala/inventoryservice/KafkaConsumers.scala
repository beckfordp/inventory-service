package inventoryservice

import cats.effect.{Async, Resource}
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.Stream
import fs2.kafka.ProducerSettings
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import purekafka.consumer.{DeadLetter, EventAge, StreamSupervisor}

/** Supervises inventory-service's first consumer, `order-events`
  * (`OrderCancelled`, US-9.4), with its own DLQ (`order-events-dlq`) and
  * restart-with-backoff, mirroring order-service's wiring.
  */
object KafkaConsumers {

  def supervised[F[_]: Async](
      config: KafkaConfig,
      store: InventoryStore[F],
      logger: StructuredLogger[F],
      meter: Meter[F],
      tracer: Tracer[F]
  ): Stream[F, Unit] = {
    def dlqProducerSettings: ProducerSettings[F, String, Option[String]] =
      ProducerSettings[F, String, Option[String]]
        .withBootstrapServers(config.bootstrapServers)

    for {
      eventAge <- Stream.eval(EventAge.create[F](meter))
      orderDeadLetter <- Stream.resource(
        DeadLetter.resource[F, String, Option[String]](
          config.dlqRetry,
          config.dlqPublishResilience,
          dlqProducerSettings
        )(logger)(meter)
      )
      fulfilmentDeadLetter <- Stream.resource(
        DeadLetter.resource[F, String, Option[String]](
          config.dlqRetry,
          config.dlqPublishResilience,
          dlqProducerSettings
        )(logger)(meter)
      )
      result <- StreamSupervisor
        .supervise[F, Unit](config.streamSupervisor)(
          logger
        )(meter)(
          Async[F].pure(
            OrderCancelledConsumer
              .run[F](config, store, logger, orderDeadLetter, eventAge, tracer)
          )
        )
        .merge(
          StreamSupervisor.supervise[F, Unit](config.streamSupervisor)(logger)(
            meter
          )(
            Async[F].pure(
              FulfilmentEventConsumer.run[F](
                config,
                store,
                logger,
                fulfilmentDeadLetter,
                eventAge,
                tracer
              )
            )
          )
        )
    } yield result
  }

  /** Backgrounded as one Resource line in Main. */
  def resource[F[_]: Async](
      config: KafkaConfig,
      store: InventoryStore[F],
      logger: StructuredLogger[F],
      meter: Meter[F],
      tracer: Tracer[F]
  ): Resource[F, Unit] =
    supervised[F](
      config,
      store,
      logger,
      meter,
      tracer
    ).compile.drain.background.void
}
