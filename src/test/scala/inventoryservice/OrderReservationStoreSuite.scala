package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite

class OrderReservationStoreSuite
    extends CatsEffectSuite
    with OrderReservationStoreContract {

  def withStore(name: String)(body: InventoryStore[IO] => IO[Unit]): Unit =
    test(name)(InventoryStore.inMemory[IO].flatMap(body))
}
