// SPDX-License-Identifier: MIT

package inventoryservice

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager

class MigrationsSuite extends CatsEffectSuite with TestContainerForAll {

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(dockerImageName =
      DockerImageName.parse("postgres:16-alpine")
    )

  test("running migrations creates the inventory table") {
    withContainers { postgres =>
      val config = PostgresConfig(
        host = postgres.host,
        port = postgres.mappedPort(5432),
        database = postgres.databaseName,
        user = postgres.username,
        password = postgres.password
      )

      Migrations.run[IO](config).map { _ =>
        val conn = DriverManager.getConnection(
          postgres.jdbcUrl,
          postgres.username,
          postgres.password
        )
        try {
          val rs = conn
            .createStatement()
            .executeQuery(
              "select column_name, data_type from information_schema.columns where table_name = 'inventory' order by ordinal_position"
            )
          val columns = Iterator
            .unfold(())(_ =>
              if (rs.next()) Some((rs.getString("column_name"), ())) else None
            )
            .toList
          assertEquals(
            columns,
            List(
              "id",
              "sku",
              "quantity_available",
              "quantity_reserved",
              "created_at",
              "updated_at"
            )
          )
        } finally conn.close()
      }
    }
  }

  test("running migrations drops the retired outbox_events table") {
    withContainers { postgres =>
      val config = PostgresConfig(
        host = postgres.host,
        port = postgres.mappedPort(5432),
        database = postgres.databaseName,
        user = postgres.username,
        password = postgres.password
      )

      Migrations.run[IO](config).map { _ =>
        val conn = DriverManager.getConnection(
          postgres.jdbcUrl,
          postgres.username,
          postgres.password
        )
        try {
          val rs = conn
            .createStatement()
            .executeQuery("select to_regclass('public.outbox_events')")
          rs.next()
          assertEquals(rs.getString(1), null)
        } finally conn.close()
      }
    }
  }

  test("running migrations creates the order_reservations ledger table") {
    withContainers { postgres =>
      val config = PostgresConfig(
        host = postgres.host,
        port = postgres.mappedPort(5432),
        database = postgres.databaseName,
        user = postgres.username,
        password = postgres.password
      )

      Migrations.run[IO](config).map { _ =>
        val conn = DriverManager.getConnection(
          postgres.jdbcUrl,
          postgres.username,
          postgres.password
        )
        try {
          val rs = conn
            .createStatement()
            .executeQuery(
              "select column_name, data_type, is_nullable from information_schema.columns where table_name = 'order_reservations' order by ordinal_position"
            )
          val columns = Iterator
            .unfold(())(_ =>
              if (rs.next())
                Some(
                  (
                    (
                      rs.getString("column_name"),
                      rs.getString("data_type"),
                      rs.getString("is_nullable")
                    ),
                    ()
                  )
                )
              else None
            )
            .toList
          assertEquals(
            columns,
            List(
              ("order_id", "uuid", "NO"),
              ("order_item_id", "uuid", "NO"),
              ("line_no", "integer", "NO"),
              ("sku", "text", "NO"),
              ("quantity", "integer", "NO"),
              ("status", "text", "NO"),
              ("created_at", "timestamp with time zone", "NO"),
              ("released_at", "timestamp with time zone", "YES")
            )
          )
          val pk = conn
            .createStatement()
            .executeQuery(
              "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey) where i.indrelid = 'order_reservations'::regclass and i.indisprimary order by a.attname"
            )
          val pkColumns = Iterator
            .unfold(())(_ =>
              if (pk.next()) Some((pk.getString(1), ())) else None
            )
            .toList
          assertEquals(pkColumns, List("order_id", "order_item_id"))
          val badStatus = scala.util.Try(
            conn
              .createStatement()
              .execute(
                "insert into order_reservations (order_id, order_item_id, line_no, sku, quantity, status) values (gen_random_uuid(), gen_random_uuid(), 0, 'x', 1, 'bogus')"
              )
          )
          assert(badStatus.isFailure, "status check constraint missing")
          val badQuantity = scala.util.Try(
            conn
              .createStatement()
              .execute(
                "insert into order_reservations (order_id, order_item_id, line_no, sku, quantity, status) values (gen_random_uuid(), gen_random_uuid(), 0, 'x', 0, 'active')"
              )
          )
          assert(badQuantity.isFailure, "quantity check constraint missing")
        } finally conn.close()
      }
    }
  }

  test(
    "migrations allow the shipped status and create inventory_reconciliation"
  ) {
    withContainers { postgres =>
      val config = PostgresConfig(
        host = postgres.host,
        port = postgres.mappedPort(5432),
        database = postgres.databaseName,
        user = postgres.username,
        password = postgres.password
      )

      Migrations.run[IO](config).map { _ =>
        val conn = DriverManager.getConnection(
          postgres.jdbcUrl,
          postgres.username,
          postgres.password
        )
        try {
          conn
            .createStatement()
            .execute(
              "insert into order_reservations (order_id, order_item_id, line_no, sku, quantity, status) values (gen_random_uuid(), gen_random_uuid(), 0, 'x', 1, 'shipped')"
            )
          val rs = conn
            .createStatement()
            .executeQuery(
              "select column_name from information_schema.columns where table_name = 'inventory_reconciliation' order by ordinal_position"
            )
          val columns = Iterator
            .unfold(())(_ =>
              if (rs.next()) Some((rs.getString("column_name"), ())) else None
            )
            .toList
          assertEquals(
            columns,
            List(
              "order_id",
              "reason",
              "created_at",
              "status",
              "operator_id",
              "note",
              "resolved_at"
            )
          )
        } finally conn.close()
      }
    }
  }

  test("migrations reject negative inventory quantities") {
    withContainers { postgres =>
      val config = PostgresConfig(
        host = postgres.host,
        port = postgres.mappedPort(5432),
        database = postgres.databaseName,
        user = postgres.username,
        password = postgres.password
      )

      Migrations.run[IO](config).map { _ =>
        val conn = DriverManager.getConnection(
          postgres.jdbcUrl,
          postgres.username,
          postgres.password
        )
        try {
          def insert(available: Int, reserved: Int) =
            scala.util.Try(
              conn
                .createStatement()
                .execute(
                  s"insert into inventory (id, sku, quantity_available, quantity_reserved) values (gen_random_uuid(), gen_random_uuid()::text, $available, $reserved)"
                )
            )
          assert(insert(0, -1).isFailure, "reserved check constraint missing")
          assert(insert(-1, 0).isFailure, "available check constraint missing")
          assert(insert(0, 0).isSuccess, "zero quantities must be allowed")
        } finally conn.close()
      }
    }
  }
}
