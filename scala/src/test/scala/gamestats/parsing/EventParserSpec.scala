package gamestats.parsing

import gamestats.TestKit
import gamestats.TestKit.*
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.funsuite.AnyFunSuite

class EventParserSpec extends AnyFunSuite:

  private val valid: String =
    """{"eventId":"3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f","timestamp":"2026-10-07T19:30:00.000Z",
      |"gameId":"snake","gameVersion":"1.0.0","matchId":"m1","playerId":"p1","type":"ACTION",
      |"action":"EAT_FOOD","data":{"foodType":"APPLE","points":10}}""".stripMargin.replace("\r", "").replace("\n", "")

  private def json(s: String): Json = parse(s).toOption.get

  test("un evento válido se parsea a un Envelope tipado") {
    val result = EventParser.parseLine(valid)
    assert(result.isRight)
    val e = result.toOption.get
    assert(e.gameId == "snake" && e.action == "EAT_FOOD" && e.playerId == "p1")
    assert(e.timestamp.toString == "2026-10-07T19:30:00Z")
    assert(e.data.hcursor.get[Int]("points") == Right(10))
  }

  test("serializar y volver a parsear conserva el evento") {
    val original = TestKit.events("snake", "m1", List(TestKit.food(1234)))
    val line     = io.circe.Printer.noSpaces.print(EnvelopeCodec.toJson(original.head))
    assert(EventParser.parseLine(line) == Right(original.head))
  }

  test("reporta todos los campos faltantes a la vez") {
    val broken = json(valid).mapObject(_.remove("eventId").remove("action"))
    val error  = EventParser.parseEnvelope(broken).left.toOption.get
    assert(error.contains("eventId") && error.contains("action"))
  }

  test("rechaza UUID inválido") {
    val broken = json(valid).mapObject(_.add("eventId", Json.fromString("no-es-uuid")))
    assert(EventParser.parseEnvelope(broken).left.exists(_.contains("UUID")))
  }

  test("rechaza timestamp que no es ISO 8601") {
    val broken = json(valid).mapObject(_.add("timestamp", Json.fromString("ayer a las tres")))
    assert(EventParser.parseEnvelope(broken).left.exists(_.contains("ISO 8601")))
  }

  test("acepta offsets horarios y los normaliza a UTC") {
    val shifted = json(valid).mapObject(_.add("timestamp", Json.fromString("2026-10-07T13:30:00-06:00")))
    assert(EventParser.parseEnvelope(shifted).map(_.timestamp.toString) == Right("2026-10-07T19:30:00Z"))
  }

  test("data debe ser un objeto") {
    val broken = json(valid).mapObject(_.add("data", Json.fromString("texto")))
    assert(EventParser.parseEnvelope(broken).left.exists(_.contains("data")))
  }

  test("campos desconocidos en el sobre se ignoran") {
    val extended = json(valid).mapObject(_.add("receivedAt", Json.fromString("2026-10-07T19:30:01Z")))
    assert(EventParser.parseEnvelope(extended).isRight)
  }

  test("un gameId nuevo no se rechaza (la validación no conoce los juegos)") {
    val chess = json(valid).mapObject(_.add("gameId", Json.fromString("ajedrez")).add("action", Json.fromString("MOVE_PIECE")))
    assert(EventParser.parseEnvelope(chess).isRight)
  }

  test("NDJSON: separa eventos válidos e inválidos indicando la línea") {
    val text   = valid + "\n\n{esto no es json}\n" + valid.replace("3f2b8c1e", "4f2b8c1e") + "\n"
    val parsed = EventParser.parseInput(text)
    assert(parsed.events.size == 2)
    assert(parsed.invalid.map(_.source) == List("línea 3"))
  }

  test("acepta arreglo JSON y lote {\"events\": [...]}") {
    val asArray = EventParser.parseInput(s"[$valid, $valid]")
    val asBatch = EventParser.parseInput(s"""{"batchId":"b1","events":[$valid]}""")
    val single  = EventParser.parseInput(valid)
    assert(asArray.events.size == 2 && asBatch.events.size == 1 && single.events.size == 1)
  }

  test("elementos inválidos de un arreglo se reportan por posición") {
    val parsed = EventParser.parseInput(s"""[$valid, {"foo": 1}]""")
    assert(parsed.events.size == 1)
    assert(parsed.invalid.map(_.source) == List("elemento 2"))
  }
