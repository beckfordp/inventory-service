package inventoryservice

import cats.data.NonEmptyList
import cats.effect.{Async, Clock, Concurrent, Ref, Resource}
import cats.effect.std.{Console, UUIDGen}
import cats.syntax.all._
import inventoryservice.domain._
import fs2.io.net.Network
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.Meter
import purerest.metrics.{LatencyBuckets, PoolMetrics}
import skunk.{Session, SqlState}
import skunk.codec.all._
import skunk.implicits._

import java.time.{Instant, OffsetDateTime}
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

  /** `DuplicateSku` if the sku already has a stock record (sku is unique). */
  def create(stock: NewStock): F[Either[DuplicateSku, Inventory]]
  def get(id: InventoryId): F[Option[Inventory]]
  def update(id: InventoryId, levels: StockUpdate): F[Option[Inventory]]
  def delete(id: InventoryId): F[Boolean]

  /** Ordered by sku. */
  def list(sku: Option[String]): F[List[Inventory]]

  /** All-or-nothing bulk reserve (TD-7.1, ADR 0018), idempotent per `orderId`:
    * if the order still holds active ledger lines they're returned unchanged
    * and nothing more is reserved; if they were released, `ReservationReleased`
    * (a late retry must not be told stock is held).
    */
  def reserveOrder(
      command: ReserveOrder
  ): F[Either[ReservationConflict, OrderReservation]]

  /** Returns every active ledger line's quantity to `quantityAvailable` and
    * marks it released (TD-7.2). Shipped lines are untouched. Unknown or
    * already-released/shipped `orderId` is a no-op, so compensation is safe to
    * retry; the outcome reports any shipped lines seen (US-21.4).
    */
  def releaseOrder(orderId: UUID): F[ReleaseOutcome]

  /** Marks active ledger lines `shipped` (US-21.4): `quantity_reserved`
    * decreases, `quantity_available` is unchanged. Reports lines already
    * shipped and lines already released.
    */
  def markShipped(orderId: UUID): F[ShipOutcome]

  /** Records a manual-reconciliation flag (US-21.4), first-wins. */
  def flagReconciliation(orderId: UUID, reason: String): F[Boolean]

  def reconciliationReason(orderId: UUID): F[Option[String]]

  /** The open/resolved reconciliation queue (ADM-4); newest first. */
  def listReconciliations(
      status: Option[ReconciliationStatus],
      orderId: Option[UUID]
  ): F[List[Reconciliation]]

  /** Resolves an open flag; the first resolver wins and a repeat returns the
    * existing row untouched.
    */
  def resolveReconciliation(
      orderId: UUID,
      operatorId: String,
      note: Option[String],
      resolvedAt: Instant
  ): F[ResolveReconciliationOutcome]

  /** Admin read of the ledger, optionally filtered by order and status
    * (`active`/`released`/`shipped`).
    */
  def listReservations(
      orderId: Option[UUID],
      status: Option[String]
  ): F[List[ReservationLedgerEntry]]

  def ping: F[Boolean]
}

object InventoryStore {

  private val defaultQuantityReserved = 0

  private final case class LedgerLine(
      line: ReservationLine,
      lineNo: Int,
      active: Boolean,
      shipped: Boolean,
      reservedAt: java.time.Instant,
      releasedAt: Option[java.time.Instant],
      shippedAt: Option[java.time.Instant]
  )

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

  /** Shipping consumes the reservation without returning stock: available is
    * unchanged, reserved decreases.
    */
  private def releaseReserved(
      entities: Map[InventoryId, Inventory],
      lines: List[ReservationLine],
      now: java.time.Instant
  ): Map[InventoryId, Inventory] = {
    val bySku = lines.groupMapReduce(_.sku)(_.quantity)(_ + _)
    entities.map { case (id, inv) =>
      bySku.get(inv.sku) match {
        case None      => id -> inv
        case Some(qty) =>
          id -> inv.copy(
            quantityReserved = inv.quantityReserved - qty,
            updatedAt = now
          )
      }
    }
  }

  def inMemory[F[_]: Concurrent: Clock: UUIDGen]: F[InventoryStore[F]] =
    for {
      state <- Ref.of[F, InMemoryState](InMemoryState(Map.empty, Map.empty))
      reconciliation <- Ref.of[F, Map[UUID, Reconciliation]](Map.empty)
    } yield {
      // Existing CRUD methods keep operating on just the stock map.
      val ref = Ref.lens[F, InMemoryState, Map[InventoryId, Inventory]](state)(
        _.inventory,
        s => inventory => s.copy(inventory = inventory)
      )
      new InventoryStore[F] {
        def create(stock: NewStock): F[Either[DuplicateSku, Inventory]] =
          for {
            id <- UUIDGen[F].randomUUID.map(InventoryId.fromUuid)
            now <- Clock[F].realTimeInstant
            entity = Inventory(
              id,
              stock.sku,
              stock.quantityAvailable,
              defaultQuantityReserved,
              now,
              now
            )
            result <- ref.modify { entities =>
              if (entities.values.exists(_.sku == stock.sku))
                (entities, Left(DuplicateSku(stock.sku)))
              else (entities + (id -> entity), Right(entity))
            }
          } yield result

        def get(id: InventoryId): F[Option[Inventory]] = ref.get.map(_.get(id))

        def update(
            id: InventoryId,
            levels: StockUpdate
        ): F[Option[Inventory]] =
          for {
            now <- Clock[F].realTimeInstant
            updated <- ref.modify { entities =>
              entities.get(id) match {
                case None           => (entities, None)
                case Some(existing) =>
                  val next =
                    existing.copy(
                      quantityAvailable = levels.quantityAvailable,
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
            val all = entities.values.toList.sortBy(_.sku)
            sku.fold(all)(filter => all.filter(_.sku == filter))
          }

        def reserveOrder(
            command: ReserveOrder
        ): F[Either[ReservationConflict, OrderReservation]] =
          Clock[F].realTimeInstant.flatMap { now =>
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
                          current.ledger + (command.orderId -> lines.zipWithIndex
                            .map { case (line, index) =>
                              LedgerLine(
                                line,
                                index,
                                active = true,
                                shipped = false,
                                now,
                                None,
                                None
                              )
                            })
                        ),
                        Right(OrderReservation(command.orderId, lines))
                      )
                  }
              }
            }
          }

        def releaseOrder(orderId: UUID): F[ReleaseOutcome] =
          Clock[F].realTimeInstant.flatMap { now =>
            state.modify { current =>
              current.ledger.get(orderId) match {
                case None        => current -> ReleaseOutcome(Nil, Nil)
                case Some(lines) =>
                  val active = lines.filter(_.active).map(_.line)
                  val shipped = lines.filter(_.shipped).map(_.line)
                  val updated = lines.map { line =>
                    if (line.active)
                      line.copy(active = false, releasedAt = Some(now))
                    else line
                  }
                  InMemoryState(
                    shiftStock(current.inventory, active, -1, now),
                    current.ledger + (orderId -> updated)
                  ) -> ReleaseOutcome(active, shipped)
              }
            }
          }

        def markShipped(orderId: UUID): F[ShipOutcome] =
          Clock[F].realTimeInstant.flatMap { now =>
            state.modify { current =>
              current.ledger.get(orderId) match {
                case None        => current -> ShipOutcome(Nil, Nil, Nil)
                case Some(lines) =>
                  val active = lines.filter(_.active).map(_.line)
                  val alreadyShipped = lines.filter(_.shipped).map(_.line)
                  val released =
                    lines
                      .filter(line => !line.active && !line.shipped)
                      .map(_.line)
                  val updated = lines.map { line =>
                    if (line.active)
                      line.copy(
                        active = false,
                        shipped = true,
                        shippedAt = Some(now)
                      )
                    else line
                  }
                  InMemoryState(
                    releaseReserved(current.inventory, active, now),
                    current.ledger + (orderId -> updated)
                  ) -> ShipOutcome(active, alreadyShipped, released)
              }
            }
          }

        def flagReconciliation(orderId: UUID, reason: String): F[Boolean] =
          state.get.map(_.ledger.contains(orderId)).flatMap {
            case false => Concurrent[F].pure(false)
            case true  =>
              Clock[F].realTimeInstant.flatMap { now =>
                reconciliation.modify { rows =>
                  if (rows.contains(orderId)) (rows, false)
                  else
                    (
                      rows + (orderId -> Reconciliation(
                        orderId,
                        reason,
                        ReconciliationStatus.Open,
                        now
                      )),
                      true
                    )
                }
              }
          }

        def reconciliationReason(orderId: UUID): F[Option[String]] =
          reconciliation.get.map(_.get(orderId).map(_.reason))

        def listReconciliations(
            status: Option[ReconciliationStatus],
            orderId: Option[UUID]
        ): F[List[Reconciliation]] =
          reconciliation.get.map(
            _.values
              .filter(row => status.forall(_ == row.status))
              .filter(row => orderId.forall(_ == row.orderId))
              .toList
              .sortBy(row => (row.createdAt, row.orderId.toString))(using
                Ordering[(Instant, String)].reverse
              )
          )

        def resolveReconciliation(
            orderId: UUID,
            operatorId: String,
            note: Option[String],
            resolvedAt: Instant
        ): F[ResolveReconciliationOutcome] =
          reconciliation.modify { rows =>
            rows.get(orderId) match {
              case None      => (rows, ResolveReconciliationOutcome.NotFound)
              case Some(row) =>
                row.resolve(operatorId, note, resolvedAt) match {
                  case Some(resolved) =>
                    (
                      rows.updated(orderId, resolved),
                      ResolveReconciliationOutcome.Resolved(resolved)
                    )
                  case None =>
                    (
                      rows,
                      ResolveReconciliationOutcome.AlreadyResolved(row)
                    )
                }
            }
          }

        def listReservations(
            orderId: Option[UUID],
            status: Option[String]
        ): F[List[ReservationLedgerEntry]] =
          state.get.map { current =>
            current.ledger.toList
              .filter { case (id, _) => orderId.forall(_ == id) }
              .flatMap { case (id, lines) =>
                lines.map { line =>
                  ReservationLedgerEntry(
                    id,
                    line.line.orderItemId,
                    line.lineNo,
                    line.line.sku,
                    line.line.quantity,
                    if (line.active) "active"
                    else if (line.shipped) "shipped"
                    else "released",
                    line.reservedAt,
                    line.releasedAt
                  )
                }
              }
              .filter(entry => status.forall(_ == entry.status))
              .sortBy(entry => (entry.orderId.toString, entry.lineNo))
          }

        def ping: F[Boolean] = Concurrent[F].pure(true)
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
    (Int, UUID),
    (String, Int, Int, OffsetDateTime, OffsetDateTime)
  ] =
    sql"""
      UPDATE "inventory"
      SET quantity_available = $int4, updated_at = now()
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
      ORDER BY sku
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

  private type LedgerEntryRow = (
      UUID,
      UUID,
      Int,
      String,
      Int,
      String,
      OffsetDateTime,
      Option[OffsetDateTime]
  )

  private val ledgerEntryRow: skunk.Decoder[LedgerEntryRow] =
    uuid *: uuid *: int4 *: text *: int4 *: text *: timestamptz *: timestamptz.opt

  private val selectReservationEntries
      : skunk.Query[(Option[UUID], Option[String]), LedgerEntryRow] =
    sql"""
      SELECT order_id, order_item_id, line_no, sku, quantity, status,
             created_at, released_at
      FROM "order_reservations"
      WHERE order_id = COALESCE(${uuid.opt}, order_id)
        AND status = COALESCE(${text.opt}, status)
      ORDER BY order_id, line_no
    """.query(ledgerEntryRow)

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

  private val markShippedCommand: skunk.Command[UUID] =
    sql"""
      UPDATE "order_reservations"
      SET status = 'shipped'
      WHERE order_id = $uuid AND status = 'active'
    """.command

  private val selectLedgerStatuses
      : skunk.Query[UUID, (UUID, String, Int, String)] =
    sql"""
      SELECT order_item_id, sku, quantity, status
      FROM "order_reservations"
      WHERE order_id = $uuid
      ORDER BY line_no
    """.query(uuid *: text *: int4 *: text)

  private val releaseReservedCommand: skunk.Command[(Int, String)] =
    sql"""
      UPDATE "inventory"
      SET quantity_reserved = quantity_reserved - $int4,
          updated_at = now()
      WHERE sku = $text
    """.command

  private val insertReconciliation: skunk.Query[(UUID, String, UUID), UUID] =
    sql"""
      INSERT INTO inventory_reconciliation (order_id, reason)
      SELECT $uuid, $text
      WHERE EXISTS (SELECT 1 FROM order_reservations WHERE order_id = $uuid)
      ON CONFLICT (order_id) DO NOTHING
      RETURNING order_id
    """.query(uuid)

  private val selectReconciliationReason: skunk.Query[UUID, String] =
    sql"""
      SELECT reason FROM inventory_reconciliation WHERE order_id = $uuid
    """.query(text)

  private val optionalText: skunk.Codec[Option[String]] = text.opt

  private val reconciliationStatusCodec: skunk.Codec[ReconciliationStatus] =
    text.eimap(ReconciliationStatus.fromString)(_.asString)

  private val reconciliationRow: skunk.Codec[
    (
        UUID,
        String,
        ReconciliationStatus,
        OffsetDateTime,
        Option[String],
        Option[String],
        Option[OffsetDateTime]
    )
  ] =
    uuid *: text *: reconciliationStatusCodec *: timestamptz *: text.opt *:
      text.opt *: timestamptz.opt

  private def toReconciliation(
      row: (
          UUID,
          String,
          ReconciliationStatus,
          OffsetDateTime,
          Option[String],
          Option[String],
          Option[OffsetDateTime]
      )
  ): Reconciliation = {
    val (orderId, reason, status, createdAt, operatorId, note, resolvedAt) =
      row
    Reconciliation(
      orderId,
      reason,
      status,
      createdAt.toInstant,
      operatorId,
      note,
      resolvedAt.map(_.toInstant)
    )
  }

  private val selectReconciliations: skunk.Query[
    (Option[String], Option[String], Option[String], Option[String]),
    (
        UUID,
        String,
        ReconciliationStatus,
        OffsetDateTime,
        Option[String],
        Option[String],
        Option[OffsetDateTime]
    )
  ] =
    sql"""
      SELECT order_id, reason, status, created_at, operator_id, note, resolved_at
      FROM inventory_reconciliation
      WHERE ($optionalText IS NULL OR status = $optionalText)
        AND ($optionalText IS NULL OR order_id = $optionalText::uuid)
      ORDER BY created_at DESC, order_id
    """.query(reconciliationRow)

  private val selectReconciliationByOrderId: skunk.Query[
    UUID,
    (
        UUID,
        String,
        ReconciliationStatus,
        OffsetDateTime,
        Option[String],
        Option[String],
        Option[OffsetDateTime]
    )
  ] =
    sql"""
      SELECT order_id, reason, status, created_at, operator_id, note, resolved_at
      FROM inventory_reconciliation
      WHERE order_id = $uuid
    """.query(reconciliationRow)

  private val resolveReconciliationQuery: skunk.Query[
    (String, Option[String], OffsetDateTime, UUID),
    (
        UUID,
        String,
        ReconciliationStatus,
        OffsetDateTime,
        Option[String],
        Option[String],
        Option[OffsetDateTime]
    )
  ] =
    sql"""
      UPDATE inventory_reconciliation
      SET status = 'resolved',
          operator_id = $text,
          note = $optionalText,
          resolved_at = $timestamptz
      WHERE order_id = $uuid
        AND status = 'open'
      RETURNING order_id, reason, status, created_at, operator_id, note,
                resolved_at
    """.query(reconciliationRow)

  private def toReservationLine(
      row: (UUID, String, Int, String)
  ): ReservationLine =
    ReservationLine(row._1, row._2, row._3)

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
      .evalMap(PoolMetrics.instrument(meter, "inventory")(_))
      .evalMap { pool =>
        meter
          .histogram[Double]("db.client.operation.duration")
          .withUnit("s")
          .withExplicitBucketBoundaries(LatencyBuckets.seconds)
          .create
          .flatMap { histogram =>
            meter
              .counter[Long]("reconciliation.flags.recorded")
              .withDescription(
                "Manual-reconciliation flags actually recorded (first-wins)."
              )
              .create
              .map { flagsCounter =>
                /** Times a Skunk query, recording a
                  * `db.client.operation.duration` measurement tagged with
                  * `db.system`/`db.operation` (OTel semantic-convention names),
                  * plus `error.type` if it fails - this is this service's only
                  * Postgres consumer, so it's instrumented directly here rather
                  * than via a new purerest combinator.
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
                  def create(
                      stock: NewStock
                  ): F[Either[DuplicateSku, Inventory]] =
                    (for {
                      id <- UUIDGen[F].randomUUID
                      timestamps <- timed("insert") {
                        pool.use { session =>
                          session
                            .prepare(insertInventory)
                            .flatMap(
                              _.unique(
                                (
                                  id,
                                  stock.sku,
                                  stock.quantityAvailable,
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
                        stock.sku,
                        stock.quantityAvailable,
                        defaultQuantityReserved,
                        createdAt.toInstant,
                        updatedAt.toInstant
                      ).asRight[DuplicateSku]
                    }).recover { case SqlState.UniqueViolation(_) =>
                      Left(DuplicateSku(stock.sku))
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
                      levels: StockUpdate
                  ): F[Option[Inventory]] =
                    timed("update") {
                      pool.use { session =>
                        session
                          .prepare(updateInventory)
                          .flatMap(
                            _.option(
                              (
                                levels.quantityAvailable,
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

                  /** Applies per-sku stock shifts in sku order (deadlock
                    * avoidance, see `selectAvailableForUpdate`).
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
                  ): F[Either[ReservationConflict, OrderReservation]] = {
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
                              .flatMap(
                                _.stream(command.orderId, 64).compile.toList
                              )
                            result <- NonEmptyList.fromList(existing) match {
                              case Some(rows) if !rows.exists(_._4) =>
                                (Left(
                                  ReservationReleased(command.orderId)
                                ): Either[
                                  ReservationConflict,
                                  OrderReservation
                                ]).pure[F]
                              case Some(rows) =>
                                (Right(
                                  OrderReservation(
                                    command.orderId,
                                    rows.toList.map {
                                      case (itemId, sku, qty, _) =>
                                        ReservationLine(itemId, sku, qty)
                                    }
                                  )
                                ): Either[
                                  ReservationConflict,
                                  OrderReservation
                                ])
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
                  ): F[Either[ReservationConflict, OrderReservation]] =
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
                            ReservationConflict,
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
                            _ <- lines.zipWithIndex.traverse_ {
                              case (line, n) =>
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
                            ReservationConflict,
                            OrderReservation
                          ])
                      }
                    } yield result

                  def releaseOrder(orderId: UUID): F[ReleaseOutcome] =
                    timed("release_order") {
                      pool.use { session =>
                        session.transaction.use { _ =>
                          for {
                            _ <- session
                              .prepare(lockOrder)
                              .flatMap(_.unique(orderId.toString))
                            before <- session
                              .prepare(selectLedgerStatuses)
                              .flatMap(_.stream(orderId, 64).compile.toList)
                            _ <- session
                              .prepare(releaseLedger)
                              .flatMap(_.stream(orderId, 64).compile.toList)
                            active = before.filter(_._4 == "active")
                            _ <- shiftAll(
                              session,
                              active
                                .groupMapReduce(_._2)(_._3)(_ + _),
                              -1
                            )
                          } yield ReleaseOutcome(
                            active.map(toReservationLine),
                            before
                              .filter(_._4 == "shipped")
                              .map(toReservationLine)
                          )
                        }
                      }
                    }

                  def markShipped(orderId: UUID): F[ShipOutcome] =
                    timed("mark_shipped") {
                      pool.use { session =>
                        session.transaction.use { _ =>
                          for {
                            _ <- session
                              .prepare(lockOrder)
                              .flatMap(_.unique(orderId.toString))
                            before <- session
                              .prepare(selectLedgerStatuses)
                              .flatMap(_.stream(orderId, 64).compile.toList)
                            active = before.filter(_._4 == "active")
                            _ <- session
                              .prepare(markShippedCommand)
                              .flatMap(_.execute(orderId))
                            _ <- session
                              .prepare(releaseReservedCommand)
                              .flatMap { cmd =>
                                active
                                  .groupMapReduce(_._2)(_._3)(_ + _)
                                  .toList
                                  .sortBy(_._1)
                                  .traverse_ { case (sku, qty) =>
                                    cmd.execute((qty, sku))
                                  }
                              }
                          } yield ShipOutcome(
                            active.map(toReservationLine),
                            before
                              .filter(_._4 == "shipped")
                              .map(toReservationLine),
                            before
                              .filter(_._4 == "released")
                              .map(toReservationLine)
                          )
                        }
                      }
                    }

                  def flagReconciliation(
                      orderId: UUID,
                      reason: String
                  ): F[Boolean] =
                    timed("flag_reconciliation") {
                      pool.use { session =>
                        session
                          .prepare(insertReconciliation)
                          .flatMap(_.option((orderId, reason, orderId)))
                          .map(_.isDefined)
                      }
                    }.flatMap {
                      case true =>
                        flagsCounter
                          .add(1L, List(Attribute("reason", reason)))
                          .attempt
                          .void
                          .as(true)
                      case false => Async[F].pure(false)
                    }

                  def listReconciliations(
                      status: Option[ReconciliationStatus],
                      orderIdFilter: Option[UUID]
                  ): F[List[Reconciliation]] =
                    timed("select_reconciliations") {
                      pool.use(
                        _.execute(selectReconciliations)(
                          (
                            status.map(_.asString),
                            status.map(_.asString),
                            orderIdFilter.map(_.toString),
                            orderIdFilter.map(_.toString)
                          )
                        )
                      )
                    }.map(_.map(toReconciliation))

                  def resolveReconciliation(
                      orderId: UUID,
                      operatorId: String,
                      note: Option[String],
                      resolvedAt: Instant
                  ): F[ResolveReconciliationOutcome] =
                    timed("resolve_reconciliation") {
                      pool.use { session =>
                        session.transaction.use { _ =>
                          session
                            .prepare(resolveReconciliationQuery)
                            .flatMap(
                              _.option(
                                (
                                  operatorId,
                                  note,
                                  OffsetDateTime.ofInstant(
                                    resolvedAt,
                                    java.time.ZoneOffset.UTC
                                  ),
                                  orderId
                                )
                              )
                            )
                            .flatMap {
                              case Some(row) =>
                                Async[F].pure(
                                  ResolveReconciliationOutcome.Resolved(
                                    toReconciliation(row)
                                  )
                                )
                              case None =>
                                session
                                  .prepare(selectReconciliationByOrderId)
                                  .flatMap(_.option(orderId))
                                  .map {
                                    case Some(row) =>
                                      ResolveReconciliationOutcome
                                        .AlreadyResolved(
                                          toReconciliation(row)
                                        )
                                    case None =>
                                      ResolveReconciliationOutcome.NotFound
                                  }
                            }
                        }
                      }
                    }

                  def reconciliationReason(orderId: UUID): F[Option[String]] =
                    timed("select_reconciliation_reason") {
                      pool.use { session =>
                        session
                          .prepare(selectReconciliationReason)
                          .flatMap(_.option(orderId))
                      }
                    }

                  def listReservations(
                      orderId: Option[UUID],
                      status: Option[String]
                  ): F[List[ReservationLedgerEntry]] =
                    timed("select_reservations") {
                      pool.use { session =>
                        session
                          .prepare(selectReservationEntries)
                          .flatMap(
                            _.stream((orderId, status), 64).compile.toList
                          )
                      }
                    }.map(
                      _.map {
                        case (
                              id,
                              itemId,
                              lineNo,
                              sku,
                              quantity,
                              lineStatus,
                              reservedAt,
                              releasedAt
                            ) =>
                          ReservationLedgerEntry(
                            id,
                            itemId,
                            lineNo,
                            sku,
                            quantity,
                            lineStatus,
                            reservedAt.toInstant,
                            releasedAt.map(_.toInstant)
                          )
                      }
                    )

                  def ping: F[Boolean] =
                    timed("ping") {
                      pool.use(_.unique(pingQuery))
                    }.attempt.map(_.isRight)
                }
              }
          }
      }
  }
}
