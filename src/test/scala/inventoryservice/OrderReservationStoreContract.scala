package inventoryservice

import cats.data.NonEmptyList
import cats.effect.IO
import munit.CatsEffectSuite

import java.util.UUID

/** Behaviour shared by both `InventoryStore` interpreters'
  * `reserveOrder`/`releaseOrder` (TD-7.1/7.2) — mixed into the in-memory and
  * Postgres suites so the two can't drift. Every test uses fresh skus, since
  * the Postgres suite shares one container.
  */
trait OrderReservationStoreContract { self: CatsEffectSuite =>

  def withStore(name: String)(body: InventoryStore[IO] => IO[Unit]): Unit

  private def sku(): String = s"sku-${UUID.randomUUID()}"

  private def line(sku: String, quantity: Int) =
    ReservationLine(UUID.randomUUID(), sku, quantity)

  private def command(first: ReservationLine, rest: ReservationLine*) =
    ReserveOrder(UUID.randomUUID(), NonEmptyList(first, rest.toList))

  private def failuresOf(
      error: OrderReservationError
  ): List[ReservationFailure] =
    error match {
      case ReservationRejected(failures) => failures.toList
      case _                             => Nil
    }

  private def levels(store: InventoryStore[IO], sku: String) =
    store
      .list(Some(sku))
      .map(_.map(i => (i.quantityAvailable, i.quantityReserved)))

  withStore("reserveOrder reserves every line and returns them") { store =>
    val (a, b) = (sku(), sku())
    val cmd = command(line(a, 2), line(b, 3))
    for {
      _ <- store.create(a, 5)
      _ <- store.create(b, 5)
      result <- store.reserveOrder(cmd)
      levelsA <- levels(store, a)
      levelsB <- levels(store, b)
    } yield {
      assertEquals(
        result,
        Right(OrderReservation(cmd.orderId, cmd.items.toList))
      )
      assertEquals(levelsA, List((3, 2)))
      assertEquals(levelsB, List((2, 3)))
    }
  }

  withStore("reserveOrder reserves nothing when any line fails") { store =>
    val (a, b) = (sku(), sku())
    val short = line(b, 3)
    for {
      _ <- store.create(a, 5)
      _ <- store.create(b, 1)
      result <- store.reserveOrder(command(line(a, 2), short))
      levelsA <- levels(store, a)
      levelsB <- levels(store, b)
    } yield {
      assertEquals(
        result,
        Left(
          ReservationRejected(
            NonEmptyList.of(
              ReservationFailure(
                short.orderItemId,
                b,
                FailureReason.InsufficientStock
              )
            )
          )
        )
      )
      assertEquals(levelsA, List((5, 0)))
      assertEquals(levelsB, List((1, 0)))
    }
  }

  withStore("reserveOrder reports every failing line in request order") {
    store =>
      val (a, b, missing) = (sku(), sku(), sku())
      val lines = List(line(a, 2), line(b, 2), line(missing, 1))
      for {
        _ <- store.create(a, 1)
        _ <- store.create(b, 1)
        result <- store.reserveOrder(command(lines(0), lines.drop(1): _*))
      } yield assertEquals(
        result.left.map(failuresOf(_).map(f => (f.orderItemId, f.reason))),
        Left(
          List(
            (lines(0).orderItemId, FailureReason.InsufficientStock),
            (lines(1).orderItemId, FailureReason.InsufficientStock),
            (lines(2).orderItemId, FailureReason.UnknownSku)
          )
        )
      )
  }

  withStore("reserveOrder sums demand for a sku across lines") { store =>
    val (tight, exact) = (sku(), sku())
    for {
      _ <- store.create(tight, 3)
      _ <- store.create(exact, 4)
      tooMuch <- store.reserveOrder(command(line(tight, 2), line(tight, 2)))
      fits <- store.reserveOrder(command(line(exact, 2), line(exact, 2)))
      levelsTight <- levels(store, tight)
      levelsExact <- levels(store, exact)
    } yield {
      assertEquals(tooMuch.left.map(failuresOf(_).size), Left(2))
      assert(fits.isRight, s"expected success, got $fits")
      assertEquals(levelsTight, List((3, 0)))
      assertEquals(levelsExact, List((0, 4)))
    }
  }

  withStore("reserveOrder is idempotent per orderId") { store =>
    val a = sku()
    val cmd = command(line(a, 2))
    for {
      _ <- store.create(a, 5)
      first <- store.reserveOrder(cmd)
      again <- store.reserveOrder(cmd)
      different <- store.reserveOrder(
        cmd.copy(items = NonEmptyList.of(line(a, 1)))
      )
      levelsA <- levels(store, a)
    } yield {
      assertEquals(again, first)
      assertEquals(different, first)
      assertEquals(levelsA, List((3, 2)))
    }
  }

  withStore("releaseOrder returns the order's stock exactly once") { store =>
    val a = sku()
    val cmd = command(line(a, 2), line(a, 1))
    for {
      _ <- store.create(a, 5)
      _ <- store.reserveOrder(cmd)
      _ <- store.releaseOrder(cmd.orderId)
      afterFirst <- levels(store, a)
      _ <- store.releaseOrder(cmd.orderId)
      afterSecond <- levels(store, a)
    } yield {
      assertEquals(afterFirst, List((5, 0)))
      assertEquals(afterSecond, List((5, 0)))
    }
  }

  withStore("reserveOrder after releaseOrder is rejected as released") {
    store =>
      val a = sku()
      val cmd = command(line(a, 2))
      for {
        _ <- store.create(a, 5)
        _ <- store.reserveOrder(cmd)
        _ <- store.releaseOrder(cmd.orderId)
        replay <- store.reserveOrder(cmd)
        levelsA <- levels(store, a)
      } yield {
        assertEquals(replay, Left(ReservationReleased(cmd.orderId)))
        assertEquals(levelsA, List((5, 0)))
      }
  }

  withStore("releaseOrder only releases its own order") { store =>
    val a = sku()
    val (first, second) = (command(line(a, 2)), command(line(a, 1)))
    for {
      _ <- store.create(a, 5)
      _ <- store.reserveOrder(first)
      _ <- store.reserveOrder(second)
      _ <- store.releaseOrder(first.orderId)
      levelsA <- levels(store, a)
    } yield assertEquals(levelsA, List((4, 1)))
  }

  withStore("releaseOrder for an unknown orderId is a no-op") { store =>
    store.releaseOrder(UUID.randomUUID())
  }
}
