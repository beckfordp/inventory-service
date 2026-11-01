package inventoryservice.domain

import munit.FunSuite

class StockLevelsSuite extends FunSuite {

  test("create accepts a non-blank sku and non-negative quantity") {
    assertEquals(
      StockLevels.create("watch-rolex-submariner", 0),
      Right(NewStock("watch-rolex-submariner", 0))
    )
  }

  test("create rejects a blank sku") {
    assertEquals(
      StockLevels.create("  ", 1),
      Left(InvalidInventory("sku must not be blank"))
    )
  }

  test("create rejects a negative quantityAvailable") {
    assertEquals(
      StockLevels.create("sku-1", -1),
      Left(InvalidInventory("quantityAvailable must not be negative"))
    )
  }

  test("update accepts non-negative quantities") {
    assertEquals(StockLevels.update(5, 2), Right(StockUpdate(5, 2)))
  }

  test("update rejects a negative quantityAvailable") {
    assertEquals(
      StockLevels.update(-1, 0),
      Left(InvalidInventory("quantityAvailable must not be negative"))
    )
  }

  test("update rejects a negative quantityReserved") {
    assertEquals(
      StockLevels.update(0, -1),
      Left(InvalidInventory("quantityReserved must not be negative"))
    )
  }

  test("parseUuid accepts a UUID and rejects anything else") {
    val raw = "3f2b8c1e-9d4a-4e7b-8a6c-1f0e2d3c4b5a"
    assertEquals(Uuids.parse(raw).map(_.toString), Some(raw))
    assertEquals(Uuids.parse("nope"), None)
  }
}
