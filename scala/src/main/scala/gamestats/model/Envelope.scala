package gamestats.model

import io.circe.Json
import java.time.Instant

/** Sobre (Envelope) común a todos los eventos, según el JSON Schema del inciso A.
  *
  * El `data` se conserva como AST de JSON: el núcleo de la plataforma nunca necesita conocer la
  * estructura del payload. Cada analizador de juego lo decodifica a sus propias case classes.
  */
final case class Envelope(
    eventId: String,
    timestamp: Instant,
    gameId: String,
    gameVersion: String,
    matchId: String,
    playerId: String,
    `type`: String,
    action: String,
    data: Json
)

object Envelope:
  /** Jugador reservado para eventos originados por el servidor o el entorno. */
  val SystemPlayer: String = "SYSTEM"

/** Categorías macro (`type`) mencionadas en el esquema. El campo es abierto: esto es solo una guía. */
object EventTypes:
  val Lifecycle   = "LIFECYCLE"
  val Input       = "INPUT"
  val Action      = "ACTION"
  val Interaction = "INTERACTION"
  val StateUpdate = "STATE_UPDATE"

/** Eventos universales de ciclo de vida, compartidos por todos los juegos. */
object Actions:
  val MatchStarted = "MATCH_STARTED"
  val MatchFinished = "MATCH_FINISHED"
  val PlayerJoined = "PLAYER_JOINED"
  val PlayerLeft = "PLAYER_LEFT"
