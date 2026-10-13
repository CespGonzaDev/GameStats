package gamestats.sim

import gamestats.model.{Actions, Envelope, EventTypes}
import io.circe.Json
import io.circe.syntax.*

import java.time.Instant

/** Identidad y reloj de una partida simulada, más constructores de eventos. */
final case class MatchSpec(gameId: String, matchId: String, start: Instant, gameVersion: String = "1.0.0"):

  def at(offsetMs: Long): Instant = start.plusMillis(offsetMs)

  def event(at: Instant, playerId: String, eventType: String, action: String, data: Json): Gen[Envelope] =
    Gen.uuid.map(id => Envelope(id, at, gameId, gameVersion, matchId, playerId, eventType, action, data))

  /** PLAYER_JOINED de cada jugador, poco antes de MATCH_STARTED. */
  def joined(players: List[String]): Gen[List[Envelope]] =
    Gen.sequence(players.zipWithIndex.map { (player, i) =>
      event(
        start.minusMillis(2000L - 400L * i),
        player,
        EventTypes.Lifecycle,
        Actions.PlayerJoined,
        Json.obj("displayName" -> player.asJson)
      )
    })

  def started(players: List[String]): Gen[Envelope] =
    event(
      start,
      gamestats.model.Envelope.SystemPlayer,
      EventTypes.Lifecycle,
      Actions.MatchStarted,
      Json.obj("players" -> players.asJson)
    )

  def finished(at: Instant, data: Json): Gen[Envelope] =
    event(at, gamestats.model.Envelope.SystemPlayer, EventTypes.Lifecycle, Actions.MatchFinished, data)
