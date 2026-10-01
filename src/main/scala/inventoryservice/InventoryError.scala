package inventoryservice

sealed trait InventoryError

case object InventoryNotFound extends InventoryError

case object InsufficientStock extends InventoryError

case object InvalidQuantity extends InventoryError
