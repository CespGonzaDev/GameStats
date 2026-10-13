package gamestats.sim

import gamestats.model.{Envelope, EventTypes}
import io.circe.Json
import io.circe.syntax.*

import java.time.Instant

/** Simula una partida de Snake con dos serpientes independientes en la misma sala. */
object SnakeSim:

  private final case class Run(foodsLeft: Int, clock: Instant, length: Int, score: Int, dir: String, done: Boolean)

  /** `n` giros consecutivos (CHANGE_DIRECTION), alternando eje vertical/horizontal. */
  private def turns(
      spec: MatchSpec,
      playerId: String,
      clock: Instant,
      dir: String,
      n: Int
  ): Gen[(List[Envelope], Instant, String)] =
    Gen
      .unfold[(Int, Instant, String), Envelope]((n, clock, dir)) { case (left, t, d) =>
        if left == 0 then Gen.pure(None)
        else
          for
            gap  <- Gen.between(400, 1200)
            next <- Gen.pick(if d == "UP" || d == "DOWN" then List("LEFT", "RIGHT") else List("UP", "DOWN"))
            at = t.plusMillis(gap)
            e <- spec.event(
              at,
              playerId,
              EventTypes.Input,
              "CHANGE_DIRECTION",
              Json.obj("from" -> d.asJson, "to" -> next.asJson)
            )
          yield Some((List(e), (left - 1, at, next)))
      }
      .map { case (events, (_, t, d)) => (events, t, d) }

  private def eat(spec: MatchSpec, playerId: String, r: Run, burst: List[Envelope], at: Instant, dir: String)
      : Gen[Option[(List[Envelope], Run)]] =
    for
      food <- Gen.weighted(List(("APPLE", 10) -> 6, ("BERRY", 15) -> 3, ("GOLDEN", 30) -> 1))
      x    <- Gen.between(0, 19)
      y    <- Gen.between(0, 19)
      e <- spec.event(
        at,
        playerId,
        EventTypes.Action,
        "EAT_FOOD",
        Json.obj(
          "foodType"      -> food._1.asJson,
          "points"        -> food._2.asJson,
          "currentLength" -> (r.length + 1).asJson,
          "coords"        -> Json.obj("x" -> x.asJson, "y" -> y.asJson)
        )
      )
    yield Some((burst :+ e, Run(r.foodsLeft - 1, at, r.length + 1, r.score + food._2, dir, done = false)))

  private def collide(spec: MatchSpec, playerId: String, r: Run, burst: List[Envelope], at: Instant, dir: String)
      : Gen[Option[(List[Envelope], Run)]] =
    for
      cause <- Gen.pick(List("SELF", "WALL"))
      e <- spec.event(
        at,
        playerId,
        EventTypes.Interaction,
        "COLLISION",
        Json.obj("collisionWith" -> cause.asJson, "finalScore" -> r.score.asJson)
      )
    yield Some((burst :+ e, r.copy(clock = at, dir = dir, done = true)))

  private def stepRun(spec: MatchSpec, playerId: String)(r: Run): Gen[Option[(List[Envelope], Run)]] =
    if r.done then Gen.pure(None)
    else
      for
        n     <- if r.foodsLeft > 0 then Gen.between(1, 6) else Gen.between(2, 5)
        burst <- turns(spec, playerId, r.clock, r.dir, n)
        gap   <- Gen.between(300, 900)
        at = burst._2.plusMillis(gap)
        out <-
          if r.foodsLeft > 0 then eat(spec, playerId, r, burst._1, at, burst._3)
          else collide(spec, playerId, r, burst._1, at, burst._3)
      yield out

  private def playerRun(spec: MatchSpec, playerId: String, begin: Instant): Gen[(List[Envelope], Int, Instant)] =
    for
      foods <- Gen.between(4, 14)
      run   <- Gen.unfold[Run, Envelope](Run(foods, begin, 3, 0, "RIGHT", done = false))(stepRun(spec, playerId))
    yield (run._1, run._2.score, run._2.clock)

  def generate(spec: MatchSpec): Gen[List[Envelope]] =
    val ids = List("p1", "p2")
    for
      joined  <- spec.joined(ids)
      begin   <- spec.started(ids)
      offsets <- Gen.sequence(ids.map(_ => Gen.between(300, 1500)))
      runs <- Gen.sequence(ids.zip(offsets).map((id, off) => playerRun(spec, id, spec.start.plusMillis(off))))
      endAt  = runs.map(_._3).maxBy(_.toEpochMilli).plusMillis(1000)
      winner = ids.zip(runs.map(_._2)).maxBy(_._2)._1
      end <- spec.finished(endAt, Json.obj("winnerId" -> winner.asJson))
    yield joined ++ (begin :: runs.flatMap(_._1)) ++ List(end)
