package inventoryservice

import cats.data.NonEmptyList
import munit.FunSuite

import java.util.UUID

class OrderReservationSuite extends FunSuite {

  private val orderId = UUID.randomUUID()
  private val itemA = UUID.randomUUID()
  private val itemB = UUID.randomUUID()

  private def line(id: UUID, sku: String, quantity: Int) =
    RequestedLine(id.toString, sku, quantity)

  test("validate accepts a well-formed request") {
    assertEquals(
      ReserveOrder.validate(
        orderId.toString,
        List(line(itemA, "sku-1", 2), line(itemB, "sku-1", 1))
      ),
      Right(
        ReserveOrder(
          orderId,
          NonEmptyList.of(
            ReservationLine(itemA, "sku-1", 2),
            ReservationLine(itemB, "sku-1", 1)
          )
        )
      )
    )
  }

  test("validate rejects a non-UUID orderId") {
    assertEquals(
      ReserveOrder.validate("nope", List(line(itemA, "sku-1", 1))),
      Left(InvalidReservation("orderId must be a UUID"))
    )
  }

  test("validate rejects empty items") {
    assertEquals(
      ReserveOrder.validate(orderId.toString, Nil),
      Left(InvalidReservation("items must not be empty"))
    )
  }

  test("validate rejects a non-UUID orderItemId") {
    assertEquals(
      ReserveOrder.validate(
        orderId.toString,
        List(RequestedLine("nope", "sku-1", 1))
      ),
      Left(InvalidReservation("orderItemId must be a UUID"))
    )
  }

  test("validate rejects a non-positive quantity") {
    assertEquals(
      ReserveOrder.validate(orderId.toString, List(line(itemA, "sku-1", 0))),
      Left(InvalidReservation("quantity must be positive"))
    )
  }

  test("validate rejects a duplicate orderItemId") {
    assertEquals(
      ReserveOrder.validate(
        orderId.toString,
        List(line(itemA, "sku-1", 1), line(itemA, "sku-2", 1))
      ),
      Left(InvalidReservation("duplicate orderItemId"))
    )
  }

  test("failures reports every line of an unknown or short sku") {
    val lines = List(
      ReservationLine(itemA, "short", 2),
      ReservationLine(itemB, "short", 2),
      ReservationLine(UUID.randomUUID(), "ok", 1),
      ReservationLine(UUID.randomUUID(), "missing", 1)
    )
    val available = Map("short" -> 3, "ok" -> 5)
    assertEquals(
      ReserveOrder.failures(lines, available).map(f => (f.sku, f.reason)),
      List(
        ("short", FailureReason.InsufficientStock),
        ("short", FailureReason.InsufficientStock),
        ("missing", FailureReason.UnknownSku)
      )
    )
  }

  test("failure reasons render as the contract's snake_case strings") {
    assertEquals(FailureReason.InsufficientStock.wire, "insufficient_stock")
    assertEquals(FailureReason.UnknownSku.wire, "unknown_sku")
  }
}
