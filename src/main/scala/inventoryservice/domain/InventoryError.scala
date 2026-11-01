package inventoryservice.domain

/** Typed failures of the inventory CRUD endpoints, mapped to HTTP statuses at
  * the edge (`InventoryRoutes`).
  */
sealed trait InventoryError

case object InventoryNotFound extends InventoryError

/** A create/update body failed `StockLevels` validation. */
final case class InvalidInventory(message: String) extends InventoryError

/** `POST /inventorys` for a sku that already has a stock record. */
final case class DuplicateSku(sku: String) extends InventoryError
