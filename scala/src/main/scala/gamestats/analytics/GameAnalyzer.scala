package gamestats.analytics

import gamestats.analytics.games.{Agario, Battleship, HeadFootball, Snake}
import gamestats.model.{Actions, Envelope, PatternDetection, PlayerReport}

import java.time.Instant

/** Todo lo que un analizador de juego necesita saber de una partida.
  * `events` ya viene deduplicado y ordenado por timestamp (ver [[Timeline.normalize]]).
  */
final case class MatchContext(
    matchId: String,
    gameId: String,
    gameVersion: String,
    events: Vector[Envelope]
):
  lazy val startedAt: Option[Instant] = events.find(_.action == Actions.MatchStarted).map(_.timestamp)
  lazy val finishedAt: Option[Instant] =
    events.reverseIterator.find(_.action == Actions.MatchFinished).map(_.timestamp)

  /** Jugadores reales (excluye `SYSTEM`), en orden de aparición. */
  lazy val players: List[String] =
    events.map(_.playerId).filter(_ != Envelope.SystemPlayer).distinct.toList

/** Lo que aporta el analizador específico de un juego. */
final case class GameAnalysis(
    players: List[PlayerReport] = Nil,
    matchMetrics: Map[String, Double] = Map.empty,
    patterns: List[PatternDetection] = Nil,
    warnings: List[String] = Nil
)

/** Punto de extensión: un juego nuevo = un `GameAnalyzer` nuevo registrado en [[GameRegistry]].
  * El pipeline común (parseo, deduplicación, ordenamiento, métricas globales, JSON) no cambia.
  */
trait GameAnalyzer:
  def gameId: String
  def analyze(ctx: MatchContext): GameAnalysis

/** Catálogo inmutable de analizadores por `gameId`. Un `gameId` sin analizador se procesa igual
  * con las métricas globales (nunca se rechaza un juego desconocido).
  */
final case class GameRegistry(analyzers: Map[String, GameAnalyzer]):
  def register(analyzer: GameAnalyzer): GameRegistry =
    copy(analyzers = analyzers.updated(analyzer.gameId.toLowerCase, analyzer))

  def find(gameId: String): Option[GameAnalyzer] = analyzers.get(gameId.toLowerCase)

object GameRegistry:
  val empty: GameRegistry = GameRegistry(Map.empty)

  val default: GameRegistry =
    List[GameAnalyzer](Snake, Agario, Battleship, HeadFootball).foldLeft(empty)(_ register _)
