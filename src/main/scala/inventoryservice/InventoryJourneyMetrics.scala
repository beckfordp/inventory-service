package inventoryservice

import cats.effect.Sync
import cats.syntax.all._
import inventoryservice.domain._
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import purerest.logging.Milestones

import java.util.UUID

/** Reservation business telemetry (gluon PERF-4.6, 4.11), as an
  * `InventoryStore` decorator — checkout reserves, cancellations release and
  * dispatch ships through the store:
  *
  *   - `inventory.reservation.outcome{outcome=reserved|insufficient|already_released}`
  *     per `reserveOrder`
  *   - `inventory.reservation.lines{outcome=released|shipped}`: ledger lines
  *     returned to stock by `releaseOrder` or shipped by `markShipped`
  *
  * Plus a `Milestones` log line per step. Order ids go on logs, never labels.
  */
object InventoryJourneyMetrics {

  /** Low-cardinality outcome label for a `reserveOrder` result. */
  def outcome(result: Either[ReservationConflict, OrderReservation]): String =
    result match {
      case Right(_)                     => "reserved"
      case Left(ReservationRejected(_)) => "insufficient"
      case Left(ReservationReleased(_)) => "already_released"
    }

  def instrument[F[_]: Sync](
      store: InventoryStore[F],
      meter: Meter[F],
      logger: StructuredLogger[F]
  ): F[InventoryStore[F]] =
    (
      meter.counter[Long]("inventory.reservation.outcome").create,
      meter.counter[Long]("inventory.reservation.lines").create
    ).mapN { (outcomes, lines) =>
      def milestone(name: String, orderId: UUID): F[Unit] =
        Milestones.log(logger)(name, orderId.toString, None)

      new InventoryStore[F] {
        export store.{reserveOrder => _, releaseOrder => _, markShipped => _, *}

        def reserveOrder(
            command: ReserveOrder
        ): F[Either[ReservationConflict, OrderReservation]] =
          store.reserveOrder(command).flatTap { result =>
            outcomes.inc(Attribute("outcome", outcome(result))) *>
              milestone("order_reserved", command.orderId).whenA(result.isRight)
          }

        def releaseOrder(orderId: UUID): F[ReleaseOutcome] =
          store.releaseOrder(orderId).flatTap { released =>
            (
              lines.add(
                released.released.size.toLong,
                Attribute("outcome", "released")
              ) *> milestone("order_released", orderId)
            ).whenA(released.released.nonEmpty)
          }

        def markShipped(orderId: UUID): F[ShipOutcome] =
          store.markShipped(orderId).flatTap { shipped =>
            (
              lines.add(
                shipped.shipped.size.toLong,
                Attribute("outcome", "shipped")
              ) *> milestone("order_shipped", orderId)
            ).whenA(shipped.shipped.nonEmpty)
          }
      }
    }
}
