package inventoryservice

sealed trait InventoryError

case object InventoryNotFound extends InventoryError
