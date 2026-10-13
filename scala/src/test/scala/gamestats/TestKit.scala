package gamestats

import gamestats.model.Envelope
import io.circe.Json
import io.circe.syntax.*

import java.time.Instant
import java.util.UUID

/** Utilidades para escribir eventos de prueba a mano, con ids y tiempos deterministas. */
object TestKit:

  val Base: Instant = Instant.parse("2026-10-07T19:30:00Z")

  /** Descripción de un evento: milisegundos desde `Base`, jugador, acción y payload. */
  final case class Spec(ms: Long, player: String, action: String, data: Json = Json.obj(), tpe: String = "ACTION")

  def events(gameId: String, matchId: String, specs: List[Spec]): List[Envelope] =
    specs.zipWithIndex.map { (s, i) =>
      Envelope(
        eventId = UUID.nameUUIDFromBytes(s"$matchId-$i".getBytes("UTF-8")).toString,
        timestamp = Base.plusMillis(s.ms),
        gameId = gameId,
        gameVersion = "1.0.0",
        matchId = matchId,
        playerId = s.player,
        `type` = s.tpe,
        action = s.action,
        data = s.data
      )
    }

  def started(ms: Long = 0): Spec  = Spec(ms, "SYSTEM", "MATCH_STARTED", tpe = "LIFECYCLE")
  def finished(ms: Long): Spec     = Spec(ms, "SYSTEM", "MATCH_FINISHED", tpe = "LIFECYCLE")

  // --- Battleship ---
  def shot(ms: Long, player: String, result: String, sunk: Option[String] = None, turn: Int = 1): Spec =
    Spec(
      ms,
      player,
      "FIRE_SHOT",
      Json.obj(
        "coord"    -> Json.obj("row" -> "C".asJson, "col" -> 4.asJson),
        "turn"     -> turn.asJson,
        "result"   -> result.asJson,
        "shipSunk" -> sunk.fold(Json.Null)(_.asJson)
      )
    )

  // --- Head Football ---
  def kick(ms: Long, player: String, shotType: String): Spec =
    Spec(ms, player, "KICK", Json.obj("shotType" -> shotType.asJson, "ballSpeed" -> 60.0.asJson, "power" -> 0.8.asJson))

  def goal(ms: Long, scorer: String, currentScore: Option[Map[String, Int]] = None): Spec =
    Spec(
      ms,
      scorer,
      "GOAL",
      Json.obj(
        "scorerId"     -> scorer.asJson,
        "minute"       -> 10.asJson,
        "currentScore" -> currentScore.fold(Json.Null)(_.asJson)
      )
    )

  // --- Snake ---
  def turn(ms: Long, player: String = "p1"): Spec =
    Spec(ms, player, "CHANGE_DIRECTION", Json.obj("from" -> "UP".asJson, "to" -> "LEFT".asJson))

  def food(ms: Long, player: String = "p1", points: Int = 10, length: Int = 4): Spec =
    Spec(
      ms,
      player,
      "EAT_FOOD",
      Json.obj("foodType" -> "APPLE".asJson, "points" -> points.asJson, "currentLength" -> length.asJson)
    )

  def collision(ms: Long, player: String, finalScore: Int): Spec =
    Spec(ms, player, "COLLISION", Json.obj("collisionWith" -> "SELF".asJson, "finalScore" -> finalScore.asJson))

  // --- Agar.io ---
  def split(ms: Long, player: String, massBefore: Double): Spec =
    Spec(ms, player, "SPLIT", Json.obj("massBefore" -> massBefore.asJson, "cellCount" -> 2.asJson))

  def eat(ms: Long, player: String, victim: String, gained: Double, total: Double): Spec =
    Spec(
      ms,
      player,
      "EAT_PLAYER",
      Json.obj("victimId" -> victim.asJson, "massGained" -> gained.asJson, "totalMass" -> total.asJson)
    )
