package inventoryservice.domain

/** A validated new stock record: built only via `StockLevels.create`. */
final case class NewStock(sku: String, quantityAvailable: Int)

/** A validated stock update: built only via `StockLevels.update`. Reserved
  * stock is owned by the reservation ledger, so it is never part of an update.
  */
final case class StockUpdate(quantityAvailable: Int)

/** Parses CRUD request bodies once at the HTTP boundary: a sku is non-blank,
  * and no quantity is negative.
  */
object StockLevels {

  private def nonNegative(
      value: Int,
      field: String
  ): Either[InvalidInventory, Int] =
    Either.cond(
      value >= 0,
      value,
      InvalidInventory(s"$field must not be negative")
    )

  def create(
      sku: String,
      quantityAvailable: Int
  ): Either[InvalidInventory, NewStock] =
    for {
      s <- Either.cond(
        sku.trim.nonEmpty,
        sku,
        InvalidInventory("sku must not be blank")
      )
      qty <- nonNegative(quantityAvailable, "quantityAvailable")
    } yield NewStock(s, qty)

  def update(quantityAvailable: Int): Either[InvalidInventory, StockUpdate] =
    nonNegative(quantityAvailable, "quantityAvailable").map(StockUpdate(_))
}
