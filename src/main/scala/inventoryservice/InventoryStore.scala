package inventoryservice

import cats.effect.{Async, Ref, Resource, Sync}
import cats.effect.std.Console
import cats.syntax.all._
import fs2.io.net.Network
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import skunk.Session
import skunk.codec.all._
import skunk.implicits._

import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.duration.SECONDS

final case class Inventory(
    id: String,
    sku: String,
    quantityAvailable: Int,
    quantityReserved: Int,
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)

trait InventoryStore[F[_]] {
  def create(sku: String, quantityAvailable: Int): F[Inventory]
  def get(id: String): F[Option[Inventory]]
  def update(
      id: String,
      quantityAvailable: Int,
      quantityReserved: Int
  ): F[Option[Inventory]]
  def delete(id: String): F[Boolean]
  def reserve(sku: String, quantity: Int): F[Either[InventoryError, Inventory]]
  def ping: F[Boolean]
}

object InventoryStore {

  private val defaultQuantityReserved = 0

  def inMemory[F[_]: Sync]: F[InventoryStore[F]] =
    Ref.of[F, Map[String, Inventory]](Map.empty).map { ref =>
      new InventoryStore[F] {
        def create(sku: String, quantityAvailable: Int): F[Inventory] =
          for {
            id <- Sync[F].delay(java.util.UUID.randomUUID().toString)
            now <- Sync[F].realTimeInstant
            entity = Inventory(
              id,
              sku,
              quantityAvailable,
              defaultQuantityReserved,
              now,
              now
            )
            _ <- ref.update(_ + (id -> entity))
          } yield entity

        def get(id: String): F[Option[Inventory]] = ref.get.map(_.get(id))

        def update(
            id: String,
            quantityAvailable: Int,
            quantityReserved: Int
        ): F[Option[Inventory]] =
          for {
            now <- Sync[F].realTimeInstant
            updated <- ref.modify { entities =>
              entities.get(id) match {
                case None           => (entities, None)
                case Some(existing) =>
                  val next =
                    existing.copy(
                      quantityAvailable = quantityAvailable,
                      quantityReserved = quantityReserved,
                      updatedAt = now
                    )
                  (entities + (id -> next), Some(next))
              }
            }
          } yield updated

        def delete(id: String): F[Boolean] =
          ref.modify { entities =>
            if (entities.contains(id)) (entities - id, true)
            else (entities, false)
          }

        def reserve(
            sku: String,
            quantity: Int
        ): F[Either[InventoryError, Inventory]] =
          for {
            now <- Sync[F].realTimeInstant
            result <- ref.modify { entities =>
              entities.values.find(_.sku == sku) match {
                case None => (entities, Left(InventoryNotFound))
                case Some(existing) if existing.quantityAvailable < quantity =>
                  (entities, Left(InsufficientStock))
                case Some(existing) =>
                  val next = existing.copy(
                    quantityAvailable = existing.quantityAvailable - quantity,
                    quantityReserved = existing.quantityReserved + quantity,
                    updatedAt = now
                  )
                  (entities + (next.id -> next), Right(next))
              }
            }
          } yield result

        def ping: F[Boolean] = Sync[F].pure(true)
      }
    }

  private val insertInventory: skunk.Query[
    (UUID, String, Int, Int),
    (OffsetDateTime, OffsetDateTime)
  ] =
    sql"""
      INSERT INTO "inventory" (id, sku, quantity_available, quantity_reserved)
      VALUES ($uuid, $text, $int4, $int4)
      RETURNING created_at, updated_at
    """.query(timestamptz *: timestamptz)

  private val selectInventory: skunk.Query[
    UUID,
    (String, Int, Int, OffsetDateTime, OffsetDateTime)
  ] =
    sql"""
      SELECT sku, quantity_available, quantity_reserved, created_at, updated_at
      FROM "inventory"
      WHERE id = $uuid
    """.query(text *: int4 *: int4 *: timestamptz *: timestamptz)

  private val updateInventory: skunk.Query[
    (Int, Int, UUID),
    (String, Int, Int, OffsetDateTime, OffsetDateTime)
  ] =
    sql"""
      UPDATE "inventory"
      SET quantity_available = $int4, quantity_reserved = $int4, updated_at = now()
      WHERE id = $uuid
      RETURNING sku, quantity_available, quantity_reserved, created_at, updated_at
    """.query(text *: int4 *: int4 *: timestamptz *: timestamptz)

  private val deleteInventory: skunk.Query[UUID, UUID] =
    sql"""
      DELETE FROM "inventory"
      WHERE id = $uuid
      RETURNING id
    """.query(uuid)

  private val pingQuery: skunk.Query[skunk.Void, Int] = sql"SELECT 1".query(
    int4
  )

  def postgres[F[_]: Async: Console: Network](
      config: PostgresConfig,
      meter: Meter[F]
  ): Resource[F, InventoryStore[F]] = {
    import org.typelevel.otel4s.trace.Tracer.Implicits.noop
    import org.typelevel.otel4s.metrics.Meter.Implicits.noop
    Session
      .Builder[F]
      .withHost(config.host)
      .withPort(config.port)
      .withUserAndPassword(config.user, config.password)
      .withDatabase(config.database)
      .pooled(max = 10)
      .evalMap { pool =>
        meter
          .histogram[Double]("db.client.operation.duration")
          .withUnit("s")
          .create
          .map { histogram =>
            /** Times a Skunk query, recording a `db.client.operation.duration`
              * measurement tagged with `db.system`/`db.operation` (OTel
              * semantic-convention names), plus `error.type` if it fails - this
              * is this service's only Postgres consumer, so it's instrumented
              * directly here rather than via a new purerest combinator.
              */
            def timed[A](operation: String)(fa: F[A]): F[A] =
              for {
                start <- Async[F].monotonic
                result <- fa.attempt
                end <- Async[F].monotonic
                outcomeAttributes = result match {
                  case Right(_)    => Nil
                  case Left(error) =>
                    List(Attribute("error.type", error.getClass.getName))
                }
                _ <- histogram.record(
                  (end - start).toUnit(SECONDS),
                  List(
                    Attribute("db.system", "postgresql"),
                    Attribute("db.operation", operation)
                  ) ++ outcomeAttributes
                )
                a <- result.liftTo[F]
              } yield a

            new InventoryStore[F] {
              def create(sku: String, quantityAvailable: Int): F[Inventory] =
                for {
                  id <- Sync[F].delay(UUID.randomUUID())
                  timestamps <- timed("insert") {
                    pool.use { session =>
                      session
                        .prepare(insertInventory)
                        .flatMap(
                          _.unique(
                            (
                              id,
                              sku,
                              quantityAvailable,
                              defaultQuantityReserved
                            )
                          )
                        )
                    }
                  }
                } yield {
                  val (createdAt, updatedAt) = timestamps
                  Inventory(
                    id.toString,
                    sku,
                    quantityAvailable,
                    defaultQuantityReserved,
                    createdAt.toInstant,
                    updatedAt.toInstant
                  )
                }

              def get(id: String): F[Option[Inventory]] =
                scala.util.Try(UUID.fromString(id)).toOption match {
                  case None       => Sync[F].pure(None)
                  case Some(uuid) =>
                    for {
                      row <- timed("select") {
                        pool.use { session =>
                          session
                            .prepare(selectInventory)
                            .flatMap(_.option(uuid))
                        }
                      }
                    } yield row.map {
                      case (
                            sku,
                            quantityAvailable,
                            quantityReserved,
                            createdAt,
                            updatedAt
                          ) =>
                        Inventory(
                          id,
                          sku,
                          quantityAvailable,
                          quantityReserved,
                          createdAt.toInstant,
                          updatedAt.toInstant
                        )
                    }
                }

              def update(
                  id: String,
                  quantityAvailable: Int,
                  quantityReserved: Int
              ): F[Option[Inventory]] =
                scala.util.Try(UUID.fromString(id)).toOption match {
                  case None       => Sync[F].pure(None)
                  case Some(uuid) =>
                    for {
                      row <- timed("update") {
                        pool.use { session =>
                          session
                            .prepare(updateInventory)
                            .flatMap(
                              _.option(
                                (quantityAvailable, quantityReserved, uuid)
                              )
                            )
                        }
                      }
                    } yield row.map {
                      case (
                            sku,
                            quantityAvailable,
                            quantityReserved,
                            createdAt,
                            updatedAt
                          ) =>
                        Inventory(
                          id,
                          sku,
                          quantityAvailable,
                          quantityReserved,
                          createdAt.toInstant,
                          updatedAt.toInstant
                        )
                    }
                }

              def delete(id: String): F[Boolean] =
                scala.util.Try(UUID.fromString(id)).toOption match {
                  case None       => Sync[F].pure(false)
                  case Some(uuid) =>
                    timed("delete") {
                      pool.use { session =>
                        session
                          .prepare(deleteInventory)
                          .flatMap(_.option(uuid))
                          .map(_.isDefined)
                      }
                    }
                }

              // Implemented in reserve-stock_20261001 Phase 3 (the atomic
              // conditional UPDATE); the in-memory store's version lands
              // first in Phase 2 so the domain/error-case work can be
              // TDD'd independently of the Postgres query.
              def reserve(
                  sku: String,
                  quantity: Int
              ): F[Either[InventoryError, Inventory]] = ???

              def ping: F[Boolean] =
                timed("ping") {
                  pool.use(_.unique(pingQuery))
                }.attempt.map(_.isRight)
            }
          }
      }
  }
}
