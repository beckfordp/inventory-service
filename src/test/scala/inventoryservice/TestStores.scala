package inventoryservice

import cats.effect.IO
import inventoryservice.domain.{NewStock, StockUpdate}

/** Test-only seeding: unwraps `create`'s `DuplicateSku` so fixtures read as one
  * call.
  */
extension (store: InventoryStore[IO]) {
  def seed(sku: String, quantityAvailable: Int): IO[Inventory] =
    store
      .create(NewStock(sku, quantityAvailable))
      .flatMap(
        _.fold(
          d => IO.raiseError(new IllegalStateException(s"duplicate ${d.sku}")),
          IO.pure
        )
      )

  def setLevels(
      id: inventoryservice.domain.InventoryId,
      quantityAvailable: Int
  ): IO[Option[Inventory]] =
    store.update(id, StockUpdate(quantityAvailable))
}
