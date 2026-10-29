package inventoryservice

import munit.FunSuite
import inventoryservice.domain.InventoryId

class InventoryIdSuite extends FunSuite {

  test("parse accepts a canonical UUID and render round-trips it") {
    val raw = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    assertEquals(InventoryId.parse(raw).map(InventoryId.render), Right(raw))
  }

  test("parse rejects a malformed id") {
    assert(InventoryId.parse("not-a-uuid").isLeft)
  }
}
