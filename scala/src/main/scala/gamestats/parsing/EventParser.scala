package gamestats.parsing

import gamestats.model.{Envelope, InvalidEvent}
import io.circe.{Json, parser}

import java.time.{Instant, OffsetDateTime}
import scala.util.Try

/** Resultado de parsear una entrada completa: eventos válidos + rechazados (con motivo). */
final case class ParsedInput(events: List[Envelope], invalid: List[InvalidEvent])

/** Parseo tipado y validación estructural del Envelope. Todas las funciones son puras:
  * los errores se devuelven como valores (`Either`), nunca como excepciones.
  *
  * Los campos desconocidos en el sobre se ignoran (tolerancia a extensiones del lado de Go).
  */
object EventParser:

  private val RequiredStrings: List[String] =
    List("eventId", "timestamp", "gameId", "gameVersion", "matchId", "playerId", "type", "action")

  private val UuidPattern =
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  private def parseTimestamp(raw: String): Either[String, Instant] =
    Try(OffsetDateTime.parse(raw).toInstant).orElse(Try(Instant.parse(raw))).toEither.left
      .map(_ => s"timestamp '$raw' no cumple ISO 8601")

  /** Valida un objeto JSON contra el esquema del Envelope. Acumula todos los errores encontrados. */
  def parseEnvelope(json: Json): Either[String, Envelope] =
    val cursor = json.hcursor

    val strings: List[(String, Either[String, String])] = RequiredStrings.map { name =>
      name -> cursor
        .get[String](name)
        .left
        .map(_ => s"campo '$name' ausente o no es texto")
        .flatMap(v => if v.trim.isEmpty then Left(s"campo '$name' vacío") else Right(v))
    }
    val values: Map[String, String] = strings.collect { case (n, Right(v)) => n -> v }.toMap

    val data: Either[String, Json] = cursor.downField("data").focus match
      case Some(d) if d.isObject => Right(d)
      case Some(_)               => Left("campo 'data' debe ser un objeto")
      case None                  => Left("campo 'data' ausente")

    val timestamp: Option[Either[String, Instant]] = values.get("timestamp").map(parseTimestamp)

    val errors: List[String] =
      strings.collect { case (_, Left(e)) => e } ++
        data.left.toOption ++
        values.get("eventId").filterNot(UuidPattern.matches).map(_ => "eventId no es un UUID válido") ++
        timestamp.flatMap(_.left.toOption)

    if errors.nonEmpty then Left(errors.mkString("; "))
    else
      (for
        ts <- timestamp.flatMap(_.toOption)
        d  <- data.toOption
      yield Envelope(
        eventId = values("eventId"),
        timestamp = ts,
        gameId = values("gameId"),
        gameVersion = values("gameVersion"),
        matchId = values("matchId"),
        playerId = values("playerId"),
        `type` = values("type"),
        action = values("action"),
        data = d
      )).toRight("evento inválido")

  /** Parsea una línea de texto (un evento JSON). */
  def parseLine(line: String): Either[String, Envelope] =
    parser.parse(line).left.map(e => s"JSON inválido: ${e.message}").flatMap(parseEnvelope)

  /** Acepta cualquiera de estos formatos de entrada (el canal Go→Scala aún no está definido):
    *
    *  1. Un arreglo JSON de eventos: `[ {...}, {...} ]`
    *  2. Un lote: `{ "events": [ {...}, ... ] }`
    *  3. Un solo evento: `{...}`
    *  4. NDJSON: un evento por línea.
    */
  def parseInput(text: String): ParsedInput =
    parser.parse(text) match
      case Right(json) => fromJson(json)
      case Left(_)     => fromLines(text)

  private def fromJson(json: Json): ParsedInput =
    json.asArray match
      case Some(items) => fromItems(items.toList)
      case None =>
        json.asObject match
          case Some(obj) =>
            obj("events").flatMap(_.asArray) match
              case Some(items) => fromItems(items.toList)
              case None        => fromItems(List(json))
          case None =>
            ParsedInput(Nil, List(InvalidEvent("entrada", "se esperaba un objeto, un arreglo o NDJSON")))

  private def fromItems(items: List[Json]): ParsedInput =
    val (invalid, valid) = items.zipWithIndex.partitionMap { (item, i) =>
      parseEnvelope(item).left.map(reason => InvalidEvent(s"elemento ${i + 1}", reason))
    }
    ParsedInput(valid, invalid)

  private def fromLines(text: String): ParsedInput =
    val numbered = text.linesIterator.zipWithIndex.map((l, i) => (l.trim, i + 1)).filter(_._1.nonEmpty).toList
    val (invalid, valid) = numbered.partitionMap { (line, n) =>
      parseLine(line).left.map(reason => InvalidEvent(s"línea $n", reason))
    }
    ParsedInput(valid, invalid)
