// SPDX-License-Identifier: MIT

package inventoryservice

import cats.effect.MonadCancelThrow
import cats.syntax.all._
import inventoryservice.domain._
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

import java.util.UUID

/** Reservation business spans (gluon PERF-5.9), as an `InventoryStore`
  * decorator over the same steps `InventoryJourneyMetrics` wraps; each is one
  * Postgres transaction, so its span is that step's DB transaction too. Wired
  * outermost, so milestone log lines nest inside. A raised error marks the span
  * Error (otel4s's default finalization).
  *
  *   - `inventory.reserve{order.id, outcome}`: outcome as the
  *     `inventory.reservation.outcome` metric labels it
  *   - `inventory.release|mark_shipped{order.id, inventory.lines}`: ledger
  *     lines released or shipped
  */
object InventoryStoreTracing {

  def instrument[F[_]: MonadCancelThrow](
      store: InventoryStore[F],
      tracer: Tracer[F]
  ): InventoryStore[F] =
    new InventoryStore[F] {
      export store.{reserveOrder => _, releaseOrder => _, markShipped => _, *}

      private def orderId(id: UUID): Attribute[String] =
        Attribute("order.id", id.toString)

      def reserveOrder(
          command: ReserveOrder
      ): F[Either[ReservationConflict, OrderReservation]] =
        tracer
          .span("inventory.reserve", orderId(command.orderId))
          .use(span =>
            store
              .reserveOrder(command)
              .flatTap(result =>
                span.addAttribute(
                  Attribute("outcome", InventoryJourneyMetrics.outcome(result))
                )
              )
          )

      def releaseOrder(id: UUID): F[ReleaseOutcome] =
        tracer
          .span("inventory.release", orderId(id))
          .use(span =>
            store
              .releaseOrder(id)
              .flatTap(released =>
                span.addAttribute(
                  Attribute("inventory.lines", released.released.size.toLong)
                )
              )
          )

      def markShipped(id: UUID): F[ShipOutcome] =
        tracer
          .span("inventory.mark_shipped", orderId(id))
          .use(span =>
            store
              .markShipped(id)
              .flatTap(shipped =>
                span.addAttribute(
                  Attribute("inventory.lines", shipped.shipped.size.toLong)
                )
              )
          )
    }
}
