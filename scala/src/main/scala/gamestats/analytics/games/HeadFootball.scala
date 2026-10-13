package gamestats.analytics.games

import gamestats.analytics.*
import gamestats.model.{Actions, Envelope, PatternDetection, PlayerReport}
import io.circe.Decoder

/** Head Football (deportes 2D en tiempo real, 1v1, reloj fijo).
  *
  * Eventos analizados:
  *  - `KICK`: `{shotType: HEADER|GROUND|LOB, ballSpeed, power}`; cada KICK cuenta como un tiro.
  *  - `GOAL`: `{scorerId, minute, currentScore}`; el goleador es `scorerId` (que debe coincidir
  *    con el `playerId` de los KICK del mismo jugador).
  *
  * Métricas por jugador (inciso C.1): `shotEffectiveness` = goles / tiros y la distribución
  * de `shotType`.
  *
  * El marcador se reconstruye a partir de los GOAL (no se confía en `currentScore`; si el
  * declarado no coincide con el recalculado, se emite una advertencia).
  *
  * Patrón (inciso C.4): `COMEBACK`.
  */
object HeadFootball extends GameAnalyzer:

  val gameId: String = "head_football"

  val Kick: String = "KICK"
  val Goal: String = "GOAL"

  /** Desventaja mínima (en goles) que debe sufrir el ganador para que cuente como remontada. */
  val MinDeficit: Int = 2

  val PatternName: String = "COMEBACK"

  final case class KickPayload(shotType: String)
  final case class GoalPayload(scorerId: String, minute: Option[Int], currentScore: Option[Map[String, Int]])

  given Decoder[KickPayload] = Decoder.forProduct1("shotType")((s: String) => KickPayload(s.toUpperCase))
  given Decoder[GoalPayload] = Decoder.forProduct3("scorerId", "minute", "currentScore")(GoalPayload.apply)

  /** Estado del marcador justo después de un gol. */
  private final case class Board(goal: Envelope, payload: GoalPayload, score: Map[String, Int])

  def analyze(ctx: MatchContext): GameAnalysis =
    val (kicks, kickWarnings) = Payloads.decode[KickPayload](ctx.events, Kick)
    val (goals, goalWarnings) = Payloads.decode[GoalPayload](ctx.events, Goal)

    val boards: Vector[Board] = scoreboards(goals)
    val players: List[String] = (ctx.players ++ goals.map(_._2.scorerId)).distinct

    val reports = players.map { playerId =>
      val myKicks = kicks.filter(_._1.playerId == playerId)
      val myGoals = goals.count(_._2.scorerId == playerId)
      PlayerReport(
        playerId = playerId,
        metrics = Metrics.collect(
          Metrics.defined("shots", myKicks.size.toDouble),
          Metrics.defined("goals", myGoals.toDouble),
          "shotEffectiveness" -> Metrics.ratio(myGoals.toDouble, myKicks.size.toDouble)
        ),
        distributions = Map("shotTypes" -> Metrics.counts(myKicks)(_._2.shotType))
      )
    }

    GameAnalysis(
      players = reports,
      matchMetrics = Metrics.collect(
        Metrics.defined("totalShots", kicks.size.toDouble),
        Metrics.defined("totalGoals", goals.size.toDouble)
      ),
      patterns = comeback(ctx, players, boards).toList,
      warnings = kickWarnings ++ goalWarnings ++ scoreMismatches(boards)
    )

  /** Marcador acumulado tras cada gol (fold sobre los GOAL en orden temporal). */
  private def scoreboards(goals: Vector[(Envelope, GoalPayload)]): Vector[Board] =
    goals
      .foldLeft((Map.empty[String, Int], Vector.empty[Board])) { case ((score, acc), (env, payload)) =>
        val next = score.updated(payload.scorerId, score.getOrElse(payload.scorerId, 0) + 1)
        (next, acc :+ Board(env, payload, next))
      }
      ._2

  private def scoreMismatches(boards: Vector[Board]): List[String] =
    boards.toList.flatMap { board =>
      board.payload.currentScore
        .map(_.filter(_._2 > 0))
        .filter(_ != board.score)
        .map(declared =>
          s"GOAL (eventId=${board.goal.eventId}): currentScore declarado $declared no coincide con el recalculado ${board.score}"
        )
    }

  /** Cuánto pierde `playerId` frente al mejor rival en un marcador dado (negativo si va ganando). */
  private def deficit(score: Map[String, Int], players: List[String], playerId: String): Int =
    val rival = players.filter(_ != playerId).map(score.getOrElse(_, 0)).maxOption.getOrElse(0)
    rival - score.getOrElse(playerId, 0)

  /** Remontada épica: el ganador final llegó a estar abajo por [[MinDeficit]] o más goles y,
    * con la secuencia posterior de GOAL, terminó ganando cuando se emitió MATCH_FINISHED.
    */
  private def comeback(ctx: MatchContext, players: List[String], boards: Vector[Board]): Option[PatternDetection] =
    for
      finishedAt <- ctx.finishedAt // la partida debe haber terminado
      if players.size >= 2 && boards.nonEmpty
      finalScore = boards.last.score
      ranking = players.sortBy(p => -finalScore.getOrElse(p, 0))
      winner <- ranking match
        case first :: second :: _ if finalScore.getOrElse(first, 0) > finalScore.getOrElse(second, 0) => Some(first)
        case _                                                                                       => None
      firstIdx = boards.indexWhere(b => deficit(b.score, players, winner) >= MinDeficit)
      if firstIdx >= 0
    yield
      val sequence   = boards.drop(firstIdx)
      val maxDeficit = boards.map(b => deficit(b.score, players, winner)).max
      val rivalFinal = players.filter(_ != winner).map(finalScore.getOrElse(_, 0)).max
      PatternDetection(
        pattern = PatternName,
        playerId = winner,
        startedAt = sequence.head.goal.timestamp,
        endedAt = finishedAt,
        eventIds = sequence.map(_.goal.eventId).toList,
        description =
          s"$winner remontó una desventaja de $maxDeficit goles y ganó ${finalScore.getOrElse(winner, 0)}-$rivalFinal",
        details = Map(
          "maxDeficit"        -> maxDeficit.toDouble,
          "winnerFinalGoals"  -> finalScore.getOrElse(winner, 0).toDouble,
          "rivalFinalGoals"   -> rivalFinal.toDouble,
          "goalsAfterDeficit" -> (sequence.size - 1).toDouble
        )
      )
