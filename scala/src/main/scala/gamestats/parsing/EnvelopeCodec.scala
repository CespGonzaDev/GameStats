package gamestats.parsing

import gamestats.model.Envelope
import io.circe.{Json, Printer}
import io.circe.syntax.*

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Serialización de eventos al formato del esquema (timestamp ISO 8601 UTC con milisegundos). */
object EnvelopeCodec:

  private val Millis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  def toJson(e: Envelope): Json =
    Json.obj(
      "eventId"     -> e.eventId.asJson,
      "timestamp"   -> Millis.format(e.timestamp).asJson,
      "gameId"      -> e.gameId.asJson,
      "gameVersion" -> e.gameVersion.asJson,
      "matchId"     -> e.matchId.asJson,
      "playerId"    -> e.playerId.asJson,
      "type"        -> e.`type`.asJson,
      "action"      -> e.action.asJson,
      "data"        -> e.data
    )

  /** Un evento por línea (NDJSON). */
  def toNdjson(events: List[Envelope]): String =
    events.map(e => Printer.noSpaces.print(toJson(e))).mkString("", "\n", "\n")

  /** Arreglo JSON legible. */
  def toJsonArray(events: List[Envelope]): String =
    Printer.spaces2.print(Json.fromValues(events.map(toJson)))
