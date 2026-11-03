package inventoryservice

import cats.data.NonEmptyList
import cats.effect.IO
import inventoryservice.domain._
import io.opentelemetry.sdk.metrics.data.MetricData
import munit.CatsEffectSuite
import org.typelevel.log4cats.testing.StructuredTestingLogger
import purerest.metrics.Metrics

import java.util.UUID
import scala.jdk.CollectionConverters._

class InventoryJourneyMetricsSuite extends CatsEffectSuite {

  private def counter(
      metrics: List[MetricData],
      name: String
  ): Map[String, Long] =
    metrics
      .find(_.getName == name)
      .map(
        _.getLongSumData.getPoints.asScala
          .map { p =>
            p.getAttributes.asMap.asScala.values.head.toString -> p.getValue
          }
          .toMap
      )
      .getOrElse(Map.empty)

  private def reserve(sku: String, qty: Int) =
    ReserveOrder(
      UUID.randomUUID(),
      NonEmptyList.one(ReservationLine(UUID.randomUUID(), sku, qty))
    )

  test(
    "counts reservation outcomes and released/shipped lines, logs milestones"
  ) {
    Metrics.test[IO]("inventory-journey").use { tm =>
      val logger = StructuredTestingLogger.impl[IO]()
      for {
        underlying <- InventoryStore.inMemory[IO]
        store <- InventoryJourneyMetrics
          .instrument[IO](underlying, tm.meter, logger)
        _ <- store.seed("sku-a", 5)
        released = reserve("sku-a", 2)
        shipped = reserve("sku-a", 1)
        _ <- store.reserveOrder(released)
        _ <- store.reserveOrder(shipped)
        _ <- store.reserveOrder(reserve("sku-a", 10))
        _ <- store.releaseOrder(released.orderId)
        _ <- store.reserveOrder(released)
        _ <- store.markShipped(shipped.orderId)
        _ <- store.releaseOrder(UUID.randomUUID())
        _ <- store.markShipped(UUID.randomUUID())
        metrics <- tm.collectMetrics
        logged <- logger.logged
      } yield {
        assertEquals(
          counter(metrics, "inventory.reservation.outcome"),
          Map("reserved" -> 2L, "insufficient" -> 1L, "already_released" -> 1L)
        )
        assertEquals(
          counter(metrics, "inventory.reservation.lines"),
          Map("released" -> 1L, "shipped" -> 1L)
        )
        assertEquals(
          logged.toList.flatMap(_.ctx.get("milestone")),
          List(
            "order_reserved",
            "order_reserved",
            "order_released",
            "order_shipped"
          )
        )
      }
    }
  }
}
