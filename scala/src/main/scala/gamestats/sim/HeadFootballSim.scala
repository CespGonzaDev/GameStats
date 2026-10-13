package gamestats.sim

import gamestats.model.{Envelope, EventTypes}
import io.circe.Json
import io.circe.syntax.*

/** Simula un partido de Head Football 1v1 de 90 "minutos" de reloj de juego (2 s reales por minuto). */
object HeadFootballSim:

  private final case class State(minute: Int, score: Map[String, Int])

  val MinuteMs: Long = 2000L

  private val ShotTypes = List("GROUND" -> 5, "HEADER" -> 3, "LOB" -> 2)

  private def goalProbability(shotType: String): Double = shotType match
    case "GROUND" => 0.30
    case "HEADER" => 0.22
    case _        => 0.15

  def scoreJson(ids: List[String], score: Map[String, Int]): Json =
    Json.obj(ids.map(id => id -> Json.fromInt(score.getOrElse(id, 0)))*)

  private def kick(spec: MatchSpec, ids: List[String], s: State): Gen[Option[(List[Envelope], State)]] =
    for
      player   <- Gen.pick(ids)
      shotType <- Gen.weighted(ShotTypes)
      speed    <- Gen.between(400, 900)
      power    <- Gen.between(30, 100)
      jitter   <- Gen.between(0, 800)
      isGoal   <- Gen.chance(goalProbability(shotType))
      kickAt = spec.at(s.minute * MinuteMs + jitter)
      kickEvent <- spec.event(
        kickAt,
        player,
        EventTypes.Action,
        "KICK",
        Json.obj("shotType" -> shotType.asJson, "ballSpeed" -> (speed / 10.0).asJson, "power" -> (power / 100.0).asJson)
      )
      newScore = if isGoal then s.score.updated(player, s.score.getOrElse(player, 0) + 1) else s.score
      goalEvents <-
        if isGoal then
          spec
            .event(
              kickAt.plusMillis(900),
              player,
              EventTypes.Interaction,
              "GOAL",
              Json.obj(
                "scorerId"     -> player.asJson,
                "minute"       -> s.minute.asJson,
                "currentScore" -> scoreJson(ids, newScore)
              )
            )
            .map(List(_))
        else Gen.pure(List.empty[Envelope])
    yield Some((kickEvent :: goalEvents, State(s.minute + 1, newScore)))

  private def step(spec: MatchSpec, ids: List[String])(s: State): Gen[Option[(List[Envelope], State)]] =
    if s.minute > 90 then Gen.pure(None)
    else
      Gen.chance(0.35).flatMap { shoots =>
        if shoots then kick(spec, ids, s)
        else Gen.pure(Some((List.empty[Envelope], s.copy(minute = s.minute + 1))))
      }

  def generate(spec: MatchSpec): Gen[List[Envelope]] =
    val ids = List("p1", "p2")
    for
      joined <- spec.joined(ids)
      begin  <- spec.started(ids)
      played <- Gen.unfold[State, Envelope](State(1, Map.empty))(step(spec, ids))
      score   = played._2.score
      winner  = ids.sortBy(id => -score.getOrElse(id, 0)) match
        case a :: b :: _ if score.getOrElse(a, 0) > score.getOrElse(b, 0) => Json.fromString(a)
        case _                                                            => Json.Null
      end <- spec.finished(
        spec.at(91 * MinuteMs),
        Json.obj("finalScore" -> scoreJson(ids, score), "winnerId" -> winner)
      )
    yield joined ++ (begin :: played._1) ++ List(end)
