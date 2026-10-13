package gamestats.analytics.games

import gamestats.analytics.*
import gamestats.model.{Envelope, PatternDetection, PlayerReport}
import io.circe.Decoder

import java.time.Instant

/** Battleship (estrategia por turnos con información oculta).
  *
  * Evento analizado: `FIRE_SHOT` con `{coord:{row,col}, turn, result: HIT|MISS, shipSunk}`.
  *
  * Métricas por jugador (inciso C.1):
  *  - `accuracy` = aciertos / disparos totales
  *  - `avgDecisionSeconds` = tiempo medio entre el disparo anterior de la partida (o MATCH_STARTED
  *    para el primero) y el disparo del jugador, es decir, lo que tarda en decidir su turno.
  *
  * Patrón (inciso C.4): `DEADLY_STREAK`.
  */
object Battleship extends GameAnalyzer:

  val gameId: String = "battleship"

  val FireShot: String = "FIRE_SHOT"

  /** Impactos consecutivos mínimos para considerar una racha crítica. */
  val MinStreak: Int = 3

  val PatternName: String = "DEADLY_STREAK"

  enum ShotResult:
    case Hit, Miss

  final case class Coord(row: String, col: Int)
  final case class Shot(coord: Coord, turn: Int, result: ShotResult, shipSunk: Option[String])

  given Decoder[ShotResult] = Decoder.decodeString.emap { raw =>
    raw.toUpperCase match
      case "HIT"  => Right(ShotResult.Hit)
      case "MISS" => Right(ShotResult.Miss)
      case other  => Left(s"result desconocido: '$other'")
  }
  given Decoder[Coord] = Decoder.forProduct2("row", "col")(Coord.apply)
  given Decoder[Shot]  = Decoder.forProduct4("coord", "turn", "result", "shipSunk")(Shot.apply)

  private type TimedShot = (Envelope, Shot)

  def analyze(ctx: MatchContext): GameAnalysis =
    val (shots, warnings) = Payloads.decode[Shot](ctx.events, FireShot)

    // Para cada disparo: instante del evento anterior (el disparo previo, o el inicio de partida).
    val previous: Vector[Option[Instant]] =
      ctx.startedAt +: shots.map((env, _) => Option(env.timestamp)).dropRight(1)

    val decisions: Vector[(String, Double)] = shots.zip(previous).flatMap { case ((env, _), prev) =>
      prev.map(p => env.playerId -> Metrics.seconds(p, env.timestamp))
    }
    val decisionsByPlayer: Map[String, Seq[Double]] =
      decisions.groupMap(_._1)(_._2)

    val shotsByPlayer: List[(String, Vector[TimedShot])] =
      shots.groupBy(_._1.playerId).toList.sortBy(_._1)

    val reports = shotsByPlayer.map { (playerId, mine) =>
      val total  = mine.size
      val hits   = mine.count(_._2.result == ShotResult.Hit)
      val misses = total - hits
      PlayerReport(
        playerId = playerId,
        metrics = Metrics.collect(
          Metrics.defined("shots", total.toDouble),
          Metrics.defined("hits", hits.toDouble),
          Metrics.defined("misses", misses.toDouble),
          Metrics.defined("shipsSunk", mine.count(_._2.shipSunk.isDefined).toDouble),
          "accuracy"           -> Metrics.ratio(hits.toDouble, total.toDouble),
          "avgDecisionSeconds" -> Metrics.mean(decisionsByPlayer.getOrElse(playerId, Nil))
        ),
        distributions = Map("results" -> Map("HIT" -> hits, "MISS" -> misses))
      )
    }

    val patterns = shotsByPlayer.flatMap((playerId, mine) => deadlyStreaks(playerId, mine))

    GameAnalysis(
      players = reports,
      matchMetrics = Metrics.collect(Metrics.defined("totalShots", shots.size.toDouble)),
      patterns = patterns,
      warnings = warnings
    )

  /** Detecta rachas críticas de un jugador.
    *
    * Una racha son los impactos consecutivos del jugador desde el último fallo (`MISS`) o el último
    * hundimiento. Se registra una detección cuando un impacto hunde un barco (`shipSunk != null`) y
    * la racha tiene al menos [[MinStreak]] impactos. Un hundimiento siempre cierra la racha (aunque
    * sea demasiado corta para detectarse), así los mismos impactos nunca se cuentan dos veces ni
    * se arrastran a la racha del siguiente barco.
    *
    * Solo cuentan los disparos del propio jugador: los del rival no interrumpen su racha.
    */
  def deadlyStreaks(playerId: String, shots: Vector[TimedShot]): List[PatternDetection] =
    final case class Acc(run: Vector[TimedShot], found: Vector[PatternDetection])

    shots
      .foldLeft(Acc(Vector.empty, Vector.empty)) { case (acc, shot @ (_, payload)) =>
        payload.result match
          case ShotResult.Miss => Acc(Vector.empty, acc.found)
          case ShotResult.Hit =>
            val run = acc.run :+ shot
            payload.shipSunk match
              case Some(ship) if run.size >= MinStreak =>
                Acc(Vector.empty, acc.found :+ detection(playerId, run, ship))
              case Some(_) => Acc(Vector.empty, acc.found) // hundió, pero la racha fue demasiado corta
              case None    => Acc(run, acc.found)
      }
      .found
      .toList

  private def detection(playerId: String, run: Vector[TimedShot], ship: String): PatternDetection =
    PatternDetection(
      pattern = PatternName,
      playerId = playerId,
      startedAt = run.head._1.timestamp,
      endedAt = run.last._1.timestamp,
      eventIds = run.map(_._1.eventId).toList,
      description = s"${run.size} impactos consecutivos sin fallos, culminando en el hundimiento de $ship",
      details = Map(
        "streakLength" -> run.size.toDouble,
        "startTurn"    -> run.head._2.turn.toDouble,
        "endTurn"      -> run.last._2.turn.toDouble
      )
    )
