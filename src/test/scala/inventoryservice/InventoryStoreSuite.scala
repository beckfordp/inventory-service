package inventoryservice

import cats.effect.IO
import munit.CatsEffectSuite
import inventoryservice.domain._

import java.util.UUID

class InventoryStoreSuite extends CatsEffectSuite {

  private def unknownInventoryId: InventoryId =
    InventoryId.fromUuid(UUID.randomUUID())

  test("create returns a persisted entity with a generated id") {
    for {
      store <- InventoryStore.inMemory[IO]
      entity <- store.seed("sku-widget-1", 100)
    } yield assert(InventoryId.render(entity.id).nonEmpty)
  }

  test("get returns the persisted entity") {
    for {
      store <- InventoryStore.inMemory[IO]
      created <- store.seed("sku-widget-1", 100)
      found <- store.get(created.id)
    } yield assertEquals(found, Some(created))
  }

  test("get returns None for an unknown id") {
    for {
      store <- InventoryStore.inMemory[IO]
      found <- store.get(unknownInventoryId)
    } yield assertEquals(found, None)
  }

  test("create produces distinct ids across calls") {
    for {
      store <- InventoryStore.inMemory[IO]
      first <- store.seed("sku-widget-1", 100)
      second <- store.seed("sku-widget-2", 100)
    } yield assertNotEquals(first.id, second.id)
  }

  test(
    "update returns the updated entity with updatedAt not moving backwards"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      created <- store.seed("sku-widget-1", 100)
      updated <- store.setLevels(created.id, 100, 0)
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
      result <- store.setLevels(unknownInventoryId, 100, 0)
    } yield assertEquals(result, None)
  }

  test(
    "delete removes the entity and returns true, and get then returns None"
  ) {
    for {
      store <- InventoryStore.inMemory[IO]
      created <- store.seed("sku-widget-1", 100)
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
      deleted <- store.delete(unknownInventoryId)
    } yield assert(!deleted)
  }

  test("list with no filter returns every created entity") {
    for {
      store <- InventoryStore.inMemory[IO]
      first <- store.seed("sku-widget-1", 100)
      second <- store.seed("sku-widget-2", 50)
      found <- store.list(None)
    } yield assertEquals(found.toSet, Set(first, second))
  }

  test("list with no entities returns an empty list") {
    for {
      store <- InventoryStore.inMemory[IO]
      found <- store.list(None)
    } yield assertEquals(found, Nil)
  }

  test("list with a sku filter returns only the matching entity") {
    for {
      store <- InventoryStore.inMemory[IO]
      widget <- store.seed("sku-widget-1", 100)
      _ <- store.seed("sku-widget-2", 50)
      found <- store.list(Some("sku-widget-1"))
    } yield assertEquals(found, List(widget))
  }

  test("list with a sku filter returns an empty list when nothing matches") {
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-widget-1", 100)
      found <- store.list(Some("sku-unknown"))
    } yield assertEquals(found, Nil)
  }

  test("create returns DuplicateSku for a sku that already exists") {
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-widget-1", 100)
      second <- store.create(NewStock("sku-widget-1", 50))
      all <- store.list(None)
    } yield {
      assertEquals(second, Left(DuplicateSku("sku-widget-1")))
      assertEquals(all.size, 1)
    }
  }

  test("list returns entities ordered by sku") {
    for {
      store <- InventoryStore.inMemory[IO]
      _ <- store.seed("sku-c", 1)
      _ <- store.seed("sku-a", 1)
      _ <- store.seed("sku-b", 1)
      found <- store.list(None)
    } yield assertEquals(found.map(_.sku), List("sku-a", "sku-b", "sku-c"))
  }
}
