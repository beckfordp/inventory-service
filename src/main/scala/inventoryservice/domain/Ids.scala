package inventoryservice.domain

import cats.syntax.all._
import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema}

import java.util.UUID

/** Opaque inventory-row identifier, parsed once at the HTTP boundary (parse,
  * don't validate - scala-fp.md law 5).
  */
opaque type InventoryId = UUID

object InventoryId {

  def parse(raw: String): Either[String, InventoryId] =
    Either
      .catchOnly[IllegalArgumentException](UUID.fromString(raw))
      .leftMap(_ => s"Invalid inventory id: '$raw'")

  def fromUuid(value: UUID): InventoryId = value

  def uuid(value: InventoryId): UUID = value

  def render(value: InventoryId): String = value.toString

  given Codec[String, InventoryId, CodecFormat.TextPlain] =
    Codec.string.mapDecode(raw =>
      parse(raw) match {
        case Right(id) => DecodeResult.Value(id)
        case Left(msg) =>
          DecodeResult.Error(raw, new IllegalArgumentException(msg))
      }
    )(render)

  given Schema[InventoryId] = Schema.string[InventoryId]
}
