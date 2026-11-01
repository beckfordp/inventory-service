package inventoryservice.domain

import cats.syntax.all._

import java.util.UUID

/** The one place raw strings become `UUID`s: only the parse failure is caught.
  */
object Uuids {
  def parse(raw: String): Option[UUID] =
    Either.catchOnly[IllegalArgumentException](UUID.fromString(raw)).toOption
}
