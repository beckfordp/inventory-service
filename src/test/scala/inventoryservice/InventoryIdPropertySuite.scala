package inventoryservice

import munit.ScalaCheckSuite
import inventoryservice.domain.InventoryId
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class InventoryIdPropertySuite extends ScalaCheckSuite {

  property("parse then render is the identity for any UUID") {
    forAll(Gen.uuid) { uuid =>
      val raw = uuid.toString
      assertEquals(InventoryId.parse(raw).map(InventoryId.render), Right(raw))
    }
  }

  property("ReserveOrder.validate never accepts an empty item list") {
    forAll(Gen.uuid) { orderId =>
      assert(
        ReserveOrder
          .validate(orderId.toString, Nil)
          .isLeft
      )
    }
  }

  property("ReserveOrder.validate rejects non-positive quantities") {
    forAll(Gen.uuid, Gen.uuid, Gen.choose(Int.MinValue, 0)) {
      (orderId, itemId, quantity) =>
        assert(
          ReserveOrder
            .validate(
              orderId.toString,
              List(RequestedLine(itemId.toString, "sku-1", quantity))
            )
            .isLeft
        )
    }
  }
}
