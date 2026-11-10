// SPDX-License-Identifier: MIT

package inventoryservice

import cats.data.NonEmptyList
import cats.effect.IO
import inventoryservice.domain._
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import purerest.tracing.Tracing

import java.util.UUID

class InventoryStoreTracingSuite extends CatsEffectSuite {

  private def reserve(sku: String, qty: Int) =
    ReserveOrder(
      UUID.randomUUID(),
      NonEmptyList.one(ReservationLine(UUID.randomUUID(), sku, qty))
    )

  private def attr(span: SpanData, key: String): Option[String] =
    Option(span.getAttributes.get(AttributeKey.stringKey(key)))

  private def lines(span: SpanData): Option[Long] =
    Option(span.getAttributes.get(AttributeKey.longKey("inventory.lines")))
      .map(_.toLong)

  test(
    "reserve, release and ship run in business spans under the caller's span, with order.id"
  ) {
    Tracing.test[IO]("inventory-store-tracing").use { tt =>
      for {
        underlying <- InventoryStore.inMemory[IO]
        store = InventoryStoreTracing.instrument[IO](underlying, tt.tracer)
        _ <- underlying.seed("sku-a", 5)
        released = reserve("sku-a", 2)
        shipped = reserve("sku-a", 1)
        tooMany = reserve("sku-a", 10)
        parentId <- tt.tracer.span("request").use { span =>
          store.reserveOrder(released) *>
            store.reserveOrder(shipped) *>
            store.reserveOrder(tooMany) *>
            store.releaseOrder(released.orderId) *>
            store.markShipped(shipped.orderId) *>
            IO.pure(span.context.spanIdHex)
        }
        spans <- tt.finishedSpans
      } yield {
        val children = spans.filter(_.getParentSpanId == parentId)
        assertEquals(
          children.map(s =>
            (s.getName, attr(s, "order.id"), attr(s, "outcome"), lines(s))
          ),
          List[(String, Option[String], Option[String], Option[Long])](
            (
              "inventory.reserve",
              Some(released.orderId.toString),
              Some("reserved"),
              None
            ),
            (
              "inventory.reserve",
              Some(shipped.orderId.toString),
              Some("reserved"),
              None
            ),
            (
              "inventory.reserve",
              Some(tooMany.orderId.toString),
              Some("insufficient"),
              None
            ),
            (
              "inventory.release",
              Some(released.orderId.toString),
              None,
              Some(1L)
            ),
            (
              "inventory.mark_shipped",
              Some(shipped.orderId.toString),
              None,
              Some(1L)
            )
          )
        )
      }
    }
  }

  test("a store error marks the span Error") {
    Tracing.test[IO]("inventory-store-tracing-error").use { tt =>
      for {
        underlying <- InventoryStore.inMemory[IO]
        failing = new InventoryStore[IO] {
          export underlying.{releaseOrder => _, *}
          def releaseOrder(orderId: UUID): IO[ReleaseOutcome] =
            IO.raiseError(new RuntimeException("db down"))
        }
        result <- InventoryStoreTracing
          .instrument[IO](failing, tt.tracer)
          .releaseOrder(UUID.randomUUID())
          .attempt
        spans <- tt.finishedSpans
      } yield {
        assert(result.isLeft)
        assertEquals(
          spans.map(s => (s.getName, s.getStatus.getStatusCode)),
          List(("inventory.release", StatusCode.ERROR))
        )
      }
    }
  }
}
