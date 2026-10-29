package inventoryservice

import cats.data.NonEmptyList
import cats.effect.{Async, Ref, Resource, Sync}
import cats.effect.std.Console
import cats.syntax.all._
import inventoryservice.domain.InventoryId
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
    id: InventoryId,
    sku: String,
    quantityAvailable: Int,
    quantityReserved: Int,
    createdAt: java.time.Instant,
    updatedAt: java.time.Instant
)

trait InventoryStore[F[_]] {
  def create(sku: String, quantityAvailable: Int): F[Inventory]
  def get(id: InventoryId): F[Option[Inventory]]
  def update(
      id: InventoryId,
      quantityAvailable: Int,
      quantityReserved: Int
  ): F[Option[Inventory]]
  def delete(id: InventoryId): F[Boolean]
  def list(sku: Option[String]): F[List[Inventory]]

  /** All-or-nothing bulk reserve (TD-7.1, ADR 0018), idempotent per `orderId`:
    * if the order still holds active ledger lines they're returned unchanged
    * and nothing more is reserved; if they were released, `ReservationReleased`
    * (a late retry must not be told stock is held).
    */
  def reserveOrder(
      command: ReserveOrder
  ): F[Either[OrderReservationError, OrderReservation]]

  /** Returns every active ledger line's quantity to `quantityAvailable` and
    * marks it released (TD-7.2). Unknown or already-released `orderId` is a
    * no-op, so compensation is safe to retry.
    */
  def releaseOrder(orderId: UUID): F[Unit]
  def ping: F[Boolean]
}

object InventoryStore {

  private val defaultQuantityReserved = 0

  private final case class LedgerLine(line: ReservationLine, active: Boolean)

  /** One `Ref` over stock and ledger together, so a bulk reserve or release
    * updates both atomically, as the Postgres transaction does.
    */
  private final case class InMemoryState(
      inventory: Map[InventoryId, Inventory],
      ledger: Map[UUID, List[LedgerLine]]
  )

  /** Applies a per-sku delta to available (and the opposite to reserved); skus
    * absent from `entities` are left alone.
    */
  private def shiftStock(
      entities: Map[InventoryId, Inventory],
      lines: List[ReservationLine],
      sign: Int,
      now: java.time.Instant
  ): Map[InventoryId, Inventory] = {
    val bySku = lines.groupMapReduce(_.sku)(_.quantity)(_ + _)
    entities.map { case (id, inv) =>
      bySku.get(inv.sku) match {
        case None      => id -> inv
        case Some(qty) =>
          id -> inv.copy(
            quantityAvailable = inv.quantityAvailable - sign * qty,
            quantityReserved = inv.quantityReserved + sign * qty,
            updatedAt = now
          )
      }
    }
  }

  def inMemory[F[_]: Sync]: F[InventoryStore[F]] =
    Ref.of[F, InMemoryState](InMemoryState(Map.empty, Map.empty)).map { state =>
      // Existing CRUD methods keep operating on just the stock map.
      val ref = Ref.lens[F, InMemoryState, Map[InventoryId, Inventory]](state)(
        _.inventory,
        s => inventory => s.copy(inventory = inventory)
      )
      new InventoryStore[F] {
        def create(sku: String, quantityAvailable: Int): F[Inventory] =
          for {
            id <- Sync[F].delay(
              InventoryId.fromUuid(java.util.UUID.randomUUID())
            )
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

        def get(id: InventoryId): F[Option[Inventory]] = ref.get.map(_.get(id))

        def update(
            id: InventoryId,
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

        def delete(id: InventoryId): F[Boolean] =
          ref.modify { entities =>
            if (entities.contains(id)) (entities - id, true)
            else (entities, false)
          }

        def list(sku: Option[String]): F[List[Inventory]] =
          ref.get.map { entities =>
            sku match {
              case None         => entities.values.toList
              case Some(filter) =>
                entities.values.filter(_.sku == filter).toList
            }
          }

        def reserveOrder(
            command: ReserveOrder
        ): F[Either[OrderReservationError, OrderReservation]] =
          Sync[F].realTimeInstant.flatMap { now =>
            state.modify { current =>
              val lines = command.items.toList
              current.ledger.get(command.orderId) match {
                case Some(existing) if !existing.exists(_.active) =>
                  (current, Left(ReservationReleased(command.orderId)))
                case Some(existing) =>
                  (
                    current,
                    Right(
                      OrderReservation(command.orderId, existing.map(_.line))
                    )
                  )
                case None =>
                  val available =
                    current.inventory.values
                      .map(i => i.sku -> i.quantityAvailable)
                      .toMap
                  NonEmptyList.fromList(
                    ReserveOrder.failures(lines, available)
                  ) match {
                    case Some(failures) =>
                      (current, Left(ReservationRejected(failures)))
                    case None =>
                      (
                        InMemoryState(
                          shiftStock(current.inventory, lines, 1, now),
                          current.ledger + (command.orderId -> lines.map(
                            LedgerLine(_, active = true)
                          ))
                        ),
                        Right(OrderReservation(command.orderId, lines))
                      )
                  }
              }
            }
          }

        def releaseOrder(orderId: UUID): F[Unit] =
          Sync[F].realTimeInstant.flatMap { now =>
            state.update { current =>
              current.ledger.get(orderId) match {
                case None        => current
                case Some(lines) =>
                  val active = lines.filter(_.active).map(_.line)
                  InMemoryState(
                    shiftStock(current.inventory, active, -1, now),
                    current.ledger + (orderId -> lines.map(
                      _.copy(active = false)
                    ))
                  )
              }
            }
          }

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

  private val selectAllInventory: skunk.Query[
    skunk.Void,
    (UUID, String, Int, Int, OffsetDateTime, OffsetDateTime)
  ] =
    sql"""
      SELECT id, sku, quantity_available, quantity_reserved, created_at, updated_at
      FROM "inventory"
    """.query(uuid *: text *: int4 *: int4 *: timestamptz *: timestamptz)

  private val selectInventoryBySku: skunk.Query[
    String,
    (UUID, String, Int, Int, OffsetDateTime, OffsetDateTime)
  ] =
    sql"""
      SELECT id, sku, quantity_available, quantity_reserved, created_at, updated_at
      FROM "inventory"
      WHERE sku = $text
    """.query(uuid *: text *: int4 *: int4 *: timestamptz *: timestamptz)

  /** Serialises concurrent bulk reserves/releases for one `orderId`, so a
    * duplicate request waits and then sees the first one's ledger lines.
    * Released automatically at commit/rollback.
    */
  private val lockOrder: skunk.Query[String, Int] =
    sql"""
      SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended($text, 0))) AS l
    """.query(int4)

  private val selectLedger: skunk.Query[UUID, (UUID, String, Int, Boolean)] =
    sql"""
      SELECT order_item_id, sku, quantity, status = 'active'
      FROM "order_reservations"
      WHERE order_id = $uuid
      ORDER BY line_no
    """.query(uuid *: text *: int4 *: bool)

  /** Callers lock skus in sorted order, so two bulk reserves over the same skus
    * can't deadlock.
    */
  private val selectAvailableForUpdate: skunk.Query[String, Int] =
    sql"""
      SELECT quantity_available FROM "inventory" WHERE sku = $text FOR UPDATE
    """.query(int4)

  /** Moves `$qty` from available to reserved (negative for a release); bound
    * twice, so the caller passes it twice.
    */
  private val shiftStockCommand: skunk.Command[(Int, Int, String)] =
    sql"""
      UPDATE "inventory"
      SET quantity_available = quantity_available - $int4,
          quantity_reserved = quantity_reserved + $int4,
          updated_at = now()
      WHERE sku = $text
    """.command

  private val insertLedgerLine: skunk.Command[(UUID, UUID, Int, String, Int)] =
    sql"""
      INSERT INTO "order_reservations" (order_id, order_item_id, line_no, sku, quantity, status)
      VALUES ($uuid, $uuid, $int4, $text, $int4, 'active')
    """.command

  private val releaseLedger: skunk.Query[UUID, (String, Int)] =
    sql"""
      UPDATE "order_reservations"
      SET status = 'released', released_at = now()
      WHERE order_id = $uuid AND status = 'active'
      RETURNING sku, quantity
    """.query(text *: int4)

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
                    InventoryId.fromUuid(id),
                    sku,
                    quantityAvailable,
                    defaultQuantityReserved,
                    createdAt.toInstant,
                    updatedAt.toInstant
                  )
                }

              def get(id: InventoryId): F[Option[Inventory]] =
                timed("select") {
                  pool.use { session =>
                    session
                      .prepare(selectInventory)
                      .flatMap(_.option(InventoryId.uuid(id)))
                  }
                }.map(_.map {
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
                })

              def update(
                  id: InventoryId,
                  quantityAvailable: Int,
                  quantityReserved: Int
              ): F[Option[Inventory]] =
                timed("update") {
                  pool.use { session =>
                    session
                      .prepare(updateInventory)
                      .flatMap(
                        _.option(
                          (
                            quantityAvailable,
                            quantityReserved,
                            InventoryId.uuid(id)
                          )
                        )
                      )
                  }
                }.map(_.map {
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
                })

              def delete(id: InventoryId): F[Boolean] =
                timed("delete") {
                  pool.use { session =>
                    session
                      .prepare(deleteInventory)
                      .flatMap(_.option(InventoryId.uuid(id)))
                      .map(_.isDefined)
                  }
                }

              def list(sku: Option[String]): F[List[Inventory]] =
                timed("list") {
                  pool.use { session =>
                    sku match {
                      case None =>
                        session
                          .prepare(selectAllInventory)
                          .flatMap(_.stream(skunk.Void, 64).compile.toList)
                      case Some(filter) =>
                        session
                          .prepare(selectInventoryBySku)
                          .flatMap(_.stream(filter, 64).compile.toList)
                    }
                  }
                }.map(_.map {
                  case (
                        id,
                        sku,
                        quantityAvailable,
                        quantityReserved,
                        createdAt,
                        updatedAt
                      ) =>
                    Inventory(
                      InventoryId.fromUuid(id),
                      sku,
                      quantityAvailable,
                      quantityReserved,
                      createdAt.toInstant,
                      updatedAt.toInstant
                    )
                })

              /** Applies per-sku stock shifts in sku order (deadlock avoidance,
                * see `selectAvailableForUpdate`).
                */
              def shiftAll(
                  session: Session[F],
                  bySku: Map[String, Int],
                  sign: Int
              ): F[Unit] =
                session.prepare(shiftStockCommand).flatMap { cmd =>
                  bySku.toList.sortBy(_._1).traverse_ { case (sku, qty) =>
                    cmd.execute((sign * qty, sign * qty, sku))
                  }
                }

              def reserveOrder(
                  command: ReserveOrder
              ): F[Either[OrderReservationError, OrderReservation]] = {
                val lines = command.items.toList
                timed("reserve_order") {
                  pool.use { session =>
                    session.transaction.use { _ =>
                      for {
                        _ <- session
                          .prepare(lockOrder)
                          .flatMap(_.unique(command.orderId.toString))
                        existing <- session
                          .prepare(selectLedger)
                          .flatMap(_.stream(command.orderId, 64).compile.toList)
                        result <- NonEmptyList.fromList(existing) match {
                          case Some(rows) if !rows.exists(_._4) =>
                            (Left(ReservationReleased(command.orderId)): Either[
                              OrderReservationError,
                              OrderReservation
                            ]).pure[F]
                          case Some(rows) =>
                            (Right(
                              OrderReservation(
                                command.orderId,
                                rows.toList.map { case (itemId, sku, qty, _) =>
                                  ReservationLine(itemId, sku, qty)
                                }
                              )
                            ): Either[OrderReservationError, OrderReservation])
                              .pure[F]
                          case None => reserveNew(session, command, lines)
                        }
                      } yield result
                    }
                  }
                }
              }

              def reserveNew(
                  session: Session[F],
                  command: ReserveOrder,
                  lines: List[ReservationLine]
              ): F[Either[OrderReservationError, OrderReservation]] =
                for {
                  select <- session.prepare(selectAvailableForUpdate)
                  available <- lines
                    .map(_.sku)
                    .distinct
                    .sorted
                    .traverse(sku => select.option(sku).map(sku -> _))
                    .map(_.collect { case (sku, Some(qty)) =>
                      sku -> qty
                    }.toMap)
                  result <- NonEmptyList.fromList(
                    ReserveOrder.failures(lines, available)
                  ) match {
                    case Some(failures) =>
                      (Left(ReservationRejected(failures)): Either[
                        OrderReservationError,
                        OrderReservation
                      ]).pure[F]
                    case None =>
                      for {
                        _ <- shiftAll(
                          session,
                          lines.groupMapReduce(_.sku)(_.quantity)(_ + _),
                          1
                        )
                        insert <- session.prepare(insertLedgerLine)
                        _ <- lines.zipWithIndex.traverse_ { case (line, n) =>
                          insert.execute(
                            (
                              command.orderId,
                              line.orderItemId,
                              n,
                              line.sku,
                              line.quantity
                            )
                          )
                        }
                      } yield (Right(
                        OrderReservation(command.orderId, lines)
                      ): Either[
                        OrderReservationError,
                        OrderReservation
                      ])
                  }
                } yield result

              def releaseOrder(orderId: UUID): F[Unit] =
                timed("release_order") {
                  pool.use { session =>
                    session.transaction.use { _ =>
                      for {
                        _ <- session
                          .prepare(lockOrder)
                          .flatMap(_.unique(orderId.toString))
                        released <- session
                          .prepare(releaseLedger)
                          .flatMap(_.stream(orderId, 64).compile.toList)
                        _ <- shiftAll(
                          session,
                          released.groupMapReduce(_._1)(_._2)(_ + _),
                          -1
                        )
                      } yield ()
                    }
                  }
                }

              def ping: F[Boolean] =
                timed("ping") {
                  pool.use(_.unique(pingQuery))
                }.attempt.map(_.isRight)
            }
          }
      }
  }
}
