package inventoryservice.domain

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class ReserveOrderPropertySuite extends ScalaCheckSuite {

  private val skus = Gen.oneOf("a", "b", "c")

  private val lines: Gen[List[ReservationLine]] =
    Gen.listOf(
      for {
        id <- Gen.uuid
        sku <- skus
        qty <- Gen.oneOf(Gen.choose(1, 10), Gen.const(Int.MaxValue))
      } yield ReservationLine(id, sku, qty)
    )

  private val available: Gen[Map[String, Int]] =
    Gen
      .listOf(Gen.zip(skus, Gen.choose(0, Int.MaxValue)))
      .map(_.toMap)

  property(
    "a line fails exactly when its sku is unknown or its summed demand exceeds stock"
  ) {
    forAll(lines, available) { (ls, stock) =>
      val demand = ls.groupMapReduce(_.sku)(_.quantity.toLong)(_ + _)
      val expected = ls.flatMap { l =>
        stock.get(l.sku) match {
          case None => List(l.orderItemId -> FailureReason.UnknownSku)
          case Some(qty) if demand(l.sku) > qty =>
            List(l.orderItemId -> FailureReason.InsufficientStock)
          case Some(_) => Nil
        }
      }
      assertEquals(
        ReserveOrder.failures(ls, stock).map(f => f.orderItemId -> f.reason),
        expected
      )
    }
  }
}
