package inventoryservice

import cats.effect.IO
import cats.syntax.all._
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.Metrics

import scala.jdk.CollectionConverters._

class InventoryStorePostgresSuite
    extends CatsEffectSuite
    with TestContainerForAll {

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

  /** All tests in this suite share a single Postgres container
    * (`TestContainerForAll`), so each test needs its own sku now that sku is
    * unique — otherwise it collides with whatever another test already
    * inserted.
    */
  private def uniqueSku(): String = s"sku-${java.util.UUID.randomUUID()}"

  test("create persists an entity and returns it with a generated id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.create(uniqueSku(), 100).map { entity =>
            assert(entity.id.nonEmpty)
          }
        }
    }
  }

  test("create produces distinct ids across calls") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations
        .run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            first <- store.create(uniqueSku(), 100)
            second <- store.create(uniqueSku(), 100)
          } yield assertNotEquals(first.id, second.id)
        }
    }
  }

  test("create fails for a sku that already exists (unique constraint)") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            _ <- store.create("sku-widget-1", 100)
            second <- store.create("sku-widget-1", 50).attempt
          } yield assert(
            second.isLeft,
            s"expected a duplicate sku to be rejected, got: $second"
          )
        }
    }
  }

  test("get returns the persisted entity") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            created <- store.create(uniqueSku(), 100)
            found <- store.get(created.id)
          } yield assertEquals(found, Some(created))
        }
    }
  }

  test("get returns None for an unknown id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store
            .get(java.util.UUID.randomUUID().toString)
            .map(assertEquals(_, None))
        }
    }
  }

  test("get returns None for a malformed (non-UUID) id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.get("not-a-uuid").map(assertEquals(_, None))
        }
    }
  }

  test("update returns the updated entity and returns it") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            created <- store.create(uniqueSku(), 100)
            updated <- store.update(created.id, 100, 0)
          } yield {
            assertEquals(updated.map(_.id), Some(created.id))
            assert(
              updated.exists(!_.updatedAt.isBefore(created.updatedAt)),
              s"expected updatedAt not to move backwards, got: $updated"
            )
          }
        }
    }
  }

  test("update returns None for an unknown id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store
            .update(java.util.UUID.randomUUID().toString, 100, 0)
            .map(assertEquals(_, None))
        }
    }
  }

  test("update returns None for a malformed (non-UUID) id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.update("not-a-uuid", 100, 0).map(assertEquals(_, None))
        }
    }
  }

  test(
    "delete removes the entity and returns true, and get then returns None"
  ) {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            created <- store.create(uniqueSku(), 100)
            deleted <- store.delete(created.id)
            found <- store.get(created.id)
          } yield {
            assert(deleted)
            assertEquals(found, None)
          }
        }
    }
  }

  test("delete returns false for an unknown id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store
            .delete(java.util.UUID.randomUUID().toString)
            .map(deleted => assert(!deleted))
        }
    }
  }

  test("delete returns false for a malformed (non-UUID) id") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.delete("not-a-uuid").map(deleted => assert(!deleted))
        }
    }
  }

  test("ping returns true against a real, reachable database") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.ping.map(assert(_))
        }
    }
  }

  test("ping returns false when the database is unreachable") {
    withContainers { postgres =>
      val unreachableConfig = configFor(postgres).copy(port = 1)
      InventoryStore
        .postgres[IO](unreachableConfig, Meter.noop[IO])
        .use { store =>
          store.ping.map(ready => assert(!ready))
        }
    }
  }

  test(
    "create and get each record a db.client.operation.duration measurement, tagged by operation"
  ) {
    withContainers { postgres =>
      val config = configFor(postgres)
      Metrics.test[IO]("inventory-store-postgres-metrics-test").use {
        testMeter =>
          Migrations.run[IO](config) *> InventoryStore
            .postgres[IO](config, testMeter.meter)
            .use { store =>
              for {
                created <- store.create(uniqueSku(), 100)
                _ <- store.get(created.id)
                metrics <- testMeter.collectMetrics
              } yield {
                val data =
                  metrics.find(_.getName == "db.client.operation.duration")
                assert(
                  data.isDefined,
                  s"expected a db.client.operation.duration series, got: $metrics"
                )
                val dbOperationKey =
                  io.opentelemetry.api.common.AttributeKey
                    .stringKey("db.operation")
                val operations = data.get.getHistogramData.getPoints.asScala
                  .flatMap(point =>
                    Option(point.getAttributes.get(dbOperationKey))
                  )
                  .toSet
                assert(
                  operations
                    .contains("insert") && operations.contains("select"),
                  s"expected db.operation attributes for both insert and select, got: $operations"
                )
              }
            }
      }
    }
  }

  test(
    "a failing query records a db.client.operation.duration measurement tagged with error.type"
  ) {
    withContainers { postgres =>
      // Port 1 is a privileged port nothing binds to in these tests; unlike
      // `mappedPort(5432) + 1`, it can't collide with another concurrently-running
      // Testcontainers Postgres instance's dynamically assigned port.
      val unreachableConfig = configFor(postgres).copy(port = 1)
      Metrics.test[IO]("inventory-store-postgres-metrics-test").use {
        testMeter =>
          InventoryStore
            .postgres[IO](unreachableConfig, testMeter.meter)
            .use { store =>
              for {
                result <- store.create(uniqueSku(), 100).attempt
                metrics <- testMeter.collectMetrics
              } yield {
                assert(
                  result.isLeft,
                  s"expected the connection failure to propagate, got: $result"
                )
                val data =
                  metrics.find(_.getName == "db.client.operation.duration")
                assert(
                  data.isDefined,
                  s"expected a db.client.operation.duration series, got: $metrics"
                )
                val errorTypeKey =
                  io.opentelemetry.api.common.AttributeKey.stringKey(
                    "error.type"
                  )
                val hasErrorAttribute =
                  data.get.getHistogramData.getPoints.asScala
                    .exists(point =>
                      Option(point.getAttributes.get(errorTypeKey)).isDefined
                    )
                assert(
                  hasErrorAttribute,
                  s"expected a point tagged with error.type, got: ${data.get.getHistogramData.getPoints}"
                )
              }
            }
      }
    }
  }

  test(
    "full CRUD lifecycle: create -> read -> patch -> delete -> read-404, plus a readiness check"
  ) {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          val sku = uniqueSku()
          for {
            ready <- store.ping
            created <- store.create(sku, 100)
            read1 <- store.get(created.id)
            updated <- store.update(created.id, 100, 0)
            read2 <- store.get(created.id)
            deleted <- store.delete(created.id)
            read3 <- store.get(created.id)
          } yield {
            assert(ready, "expected the database to be ready")
            assertEquals(read1, Some(created))
            assertEquals(updated.map(_.sku), Some(sku))
            assertEquals(updated.map(_.quantityAvailable), Some(100))
            assertEquals(updated.map(_.quantityReserved), Some(0))
            assertEquals(read2, updated)
            assert(deleted, "expected delete to report the entity existed")
            assertEquals(read3, None)
          }
        }
    }
  }

  test("reserve decrements available and increments reserved on success") {
    withContainers { postgres =>
      val config = configFor(postgres)
      val sku = uniqueSku()
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            _ <- store.create(sku, 100)
            result <- store.reserve(sku, 30)
          } yield result match {
            case Right(entity) =>
              assertEquals(entity.quantityAvailable, 70)
              assertEquals(entity.quantityReserved, 30)
            case Left(error) =>
              fail(s"expected a successful reservation, got: $error")
          }
        }
    }
  }

  test(
    "reserve returns InsufficientStock and makes no mutation when quantity exceeds available"
  ) {
    withContainers { postgres =>
      val config = configFor(postgres)
      val sku = uniqueSku()
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            created <- store.create(sku, 10)
            result <- store.reserve(sku, 20)
            after <- store.get(created.id)
          } yield {
            assertEquals(result, Left(InsufficientStock))
            assertEquals(after, Some(created))
          }
        }
    }
  }

  test("reserve returns InventoryNotFound for an unknown sku") {
    withContainers { postgres =>
      val config = configFor(postgres)
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          store.reserve(uniqueSku(), 10).map { result =>
            assertEquals(result, Left(InventoryNotFound))
          }
        }
    }
  }

  test(
    "concurrent reservations against the same sku never collectively oversell"
  ) {
    withContainers { postgres =>
      val config = configFor(postgres)
      val sku = uniqueSku()
      Migrations.run[IO](config) *> InventoryStore
        .postgres[IO](config, Meter.noop[IO])
        .use { store =>
          for {
            _ <- store.create(sku, 100)
            // 5 concurrent reservations of 30 each = 150 requested against
            // 100 available; at most 3 can succeed (3 * 30 = 90 <= 100, a
            // 4th would push it to 120).
            results <- List.fill(5)(store.reserve(sku, 30)).parSequence
            finalState <- store.get(
              results.collectFirst { case Right(entity) => entity.id }.get
            )
          } yield {
            val succeeded = results.collect { case Right(entity) => entity }
            val failed = results.collect { case Left(error) => error }
            assertEquals(succeeded.size + failed.size, 5)
            assert(
              failed.forall(_ == InsufficientStock),
              s"expected every failure to be InsufficientStock, got: $failed"
            )
            assert(
              succeeded.size * 30 <= 100,
              s"expected at most 3 successful reservations, got: ${succeeded.size}"
            )
            assertEquals(
              finalState.map(_.quantityReserved),
              Some(succeeded.size * 30)
            )
          }
        }
    }
  }
}
