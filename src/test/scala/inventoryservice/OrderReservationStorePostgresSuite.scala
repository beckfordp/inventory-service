package inventoryservice

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import org.typelevel.otel4s.metrics.Meter

import java.sql.DriverManager
import java.util.UUID

class OrderReservationStorePostgresSuite
    extends CatsEffectSuite
    with TestContainerForAll
    with OrderReservationStoreContract {

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(dockerImageName =
      DockerImageName.parse("postgres:16-alpine")
    )

  private def configFor(postgres: PostgreSQLContainer): PostgresConfig =
    PostgresConfig(
      host = postgres.host,
      port = postgres.mappedPort(5432),
      database = postgres.databaseName,
      user = postgres.username,
      password = postgres.password
    )

  private def withPostgres(
      body: (PostgreSQLContainer, InventoryStore[IO]) => IO[Unit]
  ): IO[Unit] =
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use(body(postgres, _))
    }

  def withStore(name: String)(body: InventoryStore[IO] => IO[Unit]): Unit =
    test(name)(withPostgres((_, store) => body(store)))

  private def ledger(
      postgres: PostgreSQLContainer,
      orderId: UUID
  ): IO[List[(String, Boolean)]] =
    IO.blocking {
      val conn = DriverManager.getConnection(
        postgres.jdbcUrl,
        postgres.username,
        postgres.password
      )
      try {
        val stmt = conn.prepareStatement(
          "select status, released_at is not null from order_reservations where order_id = ? order by order_item_id"
        )
        stmt.setObject(1, orderId)
        val rs = stmt.executeQuery()
        Iterator
          .unfold(())(_ =>
            if (rs.next()) Some(((rs.getString(1), rs.getBoolean(2)), ()))
            else None
          )
          .toList
      } finally conn.close()
    }

  private def sku(): String = s"sku-${UUID.randomUUID()}"

  private def single(sku: String, quantity: Int) =
    ReserveOrder(
      UUID.randomUUID(),
      NonEmptyList.of(ReservationLine(UUID.randomUUID(), sku, quantity))
    )

  test(
    "reserveOrder writes active ledger lines; releaseOrder marks them released"
  ) {
    withPostgres { (postgres, store) =>
      val a = sku()
      val cmd = ReserveOrder(
        UUID.randomUUID(),
        NonEmptyList.of(
          ReservationLine(UUID.randomUUID(), a, 1),
          ReservationLine(UUID.randomUUID(), a, 1)
        )
      )
      for {
        _ <- store.create(a, 5)
        _ <- store.reserveOrder(cmd)
        reserved <- ledger(postgres, cmd.orderId)
        _ <- store.releaseOrder(cmd.orderId)
        released <- ledger(postgres, cmd.orderId)
      } yield {
        assertEquals(reserved, List(("active", false), ("active", false)))
        assertEquals(released, List(("released", true), ("released", true)))
      }
    }
  }

  test("concurrent orders competing for the last units never oversell") {
    withPostgres { (_, store) =>
      val a = sku()
      for {
        _ <- store.create(a, 3)
        results <- List.fill(8)(single(a, 2)).parTraverse(store.reserveOrder)
        levels <- store.list(Some(a))
      } yield {
        assertEquals(results.count(_.isRight), 1)
        assertEquals(
          levels.map(i => (i.quantityAvailable, i.quantityReserved)),
          List((1, 2))
        )
      }
    }
  }

  test("concurrent duplicate calls for one orderId reserve once") {
    withPostgres { (postgres, store) =>
      val a = sku()
      val cmd = single(a, 2)
      for {
        _ <- store.create(a, 10)
        results <- List.fill(6)(cmd).parTraverse(store.reserveOrder)
        levels <- store.list(Some(a))
        lines <- ledger(postgres, cmd.orderId)
      } yield {
        assert(
          results.forall(_ == results.head),
          s"diverging results: $results"
        )
        assert(results.head.isRight, s"expected success, got ${results.head}")
        assertEquals(
          levels.map(i => (i.quantityAvailable, i.quantityReserved)),
          List((8, 2))
        )
        assertEquals(lines.size, 1)
      }
    }
  }

  test("concurrent orders over shared skus in opposite order don't deadlock") {
    withPostgres { (_, store) =>
      val (a, b) = (sku(), sku())
      val orders = List.tabulate(10) { n =>
        val (first, second) = if (n % 2 == 0) (a, b) else (b, a)
        ReserveOrder(
          UUID.randomUUID(),
          NonEmptyList.of(
            ReservationLine(UUID.randomUUID(), first, 1),
            ReservationLine(UUID.randomUUID(), second, 1)
          )
        )
      }
      for {
        _ <- store.create(a, 100)
        _ <- store.create(b, 100)
        results <- orders.parTraverse(store.reserveOrder)
        _ <- orders.parTraverse_(o => store.releaseOrder(o.orderId))
        levels <- (store.list(Some(a)), store.list(Some(b))).tupled
      } yield {
        assert(results.forall(_.isRight), s"unexpected failure: $results")
        assertEquals(
          (levels._1 ++ levels._2).map(i =>
            (i.quantityAvailable, i.quantityReserved)
          ),
          List((100, 0), (100, 0))
        )
      }
    }
  }
}
