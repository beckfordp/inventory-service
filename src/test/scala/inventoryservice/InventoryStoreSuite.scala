package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite

class InventoryStoreSuite extends CatsEffectSuite {

  test("create returns a persisted entity with a generated id") {
    for {
      store <- InventoryStore.inMemory[IO]
      entity <- store.create("sku-widget-1", 100)
    } yield assert(entity.id.nonEmpty)
  }

  test("get returns the persisted entity") {
    for {
      store <- InventoryStore.inMemory[IO]
      created <- store.create("sku-widget-1", 100)
      found <- store.get(created.id)
    } yield assertEquals(found, Some(created))
  }

  test("get returns None for an unknown id") {
    for {
      store <- InventoryStore.inMemory[IO]
      found <- store.get("unknown-id")
    } yield assertEquals(found, None)
  }

  test("create produces distinct ids across calls") {
    for {
      store <- InventoryStore.inMemory[IO]
      first <- store.create("sku-widget-1", 100)
      second <- store.create("sku-widget-1", 100)
    } yield assertNotEquals(first.id, second.id)
  }

  test(
    "update returns the updated entity with updatedAt not moving backwards"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      created <- store.create("sku-widget-1", 100)
      updated <- store.update(created.id, 100, 0)
    } yield {
      assertEquals(updated.map(_.id), Some(created.id))
      assert(
        updated.exists(!_.updatedAt.isBefore(created.updatedAt)),
        s"expected updatedAt not to move backwards, got: $updated"
      )
    }
  }

  test("update returns None for an unknown id") {
    for {
      store <- InventoryStore.inMemory[IO]
      result <- store.update("unknown-id", 100, 0)
    } yield assertEquals(result, None)
  }

  test(
    "delete removes the entity and returns true, and get then returns None"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      created <- store.create("sku-widget-1", 100)
      deleted <- store.delete(created.id)
      found <- store.get(created.id)
    } yield {
      assert(deleted)
      assertEquals(found, None)
    }
  }

  test("delete returns false for an unknown id") {
    for {
      store <- InventoryStore.inMemory[IO]
      deleted <- store.delete("unknown-id")
    } yield assert(!deleted)
  }

  test(
    "reserve decrements quantityAvailable and increments quantityReserved on success"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.create("sku-widget-1", 100)
      result <- store.reserve("sku-widget-1", 30)
    } yield result match {
      case Right(entity) =>
        assertEquals(entity.quantityAvailable, 70)
        assertEquals(entity.quantityReserved, 30)
      case Left(error) =>
        fail(s"expected a successful reservation, got: $error")
    }
  }

  test(
    "reserve returns InsufficientStock when quantity exceeds quantityAvailable"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.create("sku-widget-1", 10)
      result <- store.reserve("sku-widget-1", 20)
    } yield assertEquals(result, Left(InsufficientStock))
  }

  test("reserve returns InventoryNotFound for an unknown sku") {
    for {
      store <- InventoryStore.inMemory[IO]
      result <- store.reserve("unknown-sku", 10)
    } yield assertEquals(result, Left(InventoryNotFound))
  }

  test("reserve returns InvalidQuantity for a zero or negative quantity") {
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.create("sku-widget-1", 100)
      zero <- store.reserve("sku-widget-1", 0)
      negative <- store.reserve("sku-widget-1", -5)
    } yield {
      assertEquals(zero, Left(InvalidQuantity))
      assertEquals(negative, Left(InvalidQuantity))
    }
  }

  test("reserve with an invalid quantity makes no mutation") {
    for {
      store <- InventoryStore.inMemory[IO]
      created <- store.create("sku-widget-1", 100)
      _ <- store.reserve("sku-widget-1", -5)
      after <- store.get(created.id)
    } yield assertEquals(after, Some(created))
  }
}
