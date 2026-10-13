package gamestats.sim

import gamestats.model.{Actions, Envelope, EventTypes}
import io.circe.Json
import io.circe.syntax.*

import java.time.Instant

/** Simula una sala de Agar.io con 3 a 5 jugadores.
  *
  * Tras un SPLIT, el jugador "se arriesga": con 60 % de probabilidad su siguiente acción es un
  * EAT_PLAYER, que casi siempre cae dentro de la ventana de 10 s y a veces (25 %) fuera de ella,
  * para ejercitar el límite de la métrica de conversión de riesgo.
  */
object AgarioSim:

  private final case class State(
      masses: Map[String, Double],
      clock: Instant,
      forced: Option[(String, Long)], // (jugador, milisegundos hasta su próximo EAT_PLAYER)
      tick: Int
  )

  private val MaxTicks = 90

  private def round1(d: Double): Double = BigDecimal(d).setScale(1, BigDecimal.RoundingMode.HALF_UP).toDouble

  private def idle(s: State, gapMs: Long): Gen[Option[(List[Envelope], State)]] =
    Gen.pure(Some((Nil, s.copy(clock = s.clock.plusMillis(gapMs), forced = None, tick = s.tick + 1))))

  private def doSplit(spec: MatchSpec, s: State, actor: String): Gen[Option[(List[Envelope], State)]] =
    for
      gap    <- Gen.between(1000, 3000)
      vx     <- Gen.between(-100, 100)
      vy     <- Gen.between(-100, 100)
      follow <- Gen.chance(0.6)
      inside <- Gen.chance(0.75)
      delay  <- if inside then Gen.between(1500, 8000) else Gen.between(11000, 18000)
      at = s.clock.plusMillis(gap)
      e <- spec.event(
        at,
        actor,
        EventTypes.Interaction,
        "SPLIT",
        Json.obj(
          "massBefore" -> s.masses(actor).asJson,
          "cellCount"  -> 2.asJson,
          "vector"     -> Json.obj("x" -> (vx / 100.0).asJson, "y" -> (vy / 100.0).asJson)
        )
      )
    yield Some((List(e), s.copy(clock = at, forced = Option.when(follow)((actor, delay.toLong)), tick = s.tick + 1)))

  private def doEat(spec: MatchSpec, s: State, actor: String, gapMs: Long): Gen[Option[(List[Envelope], State)]] =
    val actorMass = s.masses(actor)
    val victims   = s.masses.toList.filter((id, m) => id != actor && m < actorMass).map(_._1).sorted
    if victims.isEmpty then idle(s, gapMs)
    else
      for
        victim <- Gen.pick(victims)
        at      = s.clock.plusMillis(gapMs)
        gained  = round1(s.masses(victim) * 0.8)
        total   = round1(actorMass + gained)
        eaten <- spec.event(
          at,
          actor,
          EventTypes.Interaction,
          "EAT_PLAYER",
          Json.obj("victimId" -> victim.asJson, "massGained" -> gained.asJson, "totalMass" -> total.asJson)
        )
        left <- spec.event(
          at.plusMillis(1),
          victim,
          EventTypes.Lifecycle,
          Actions.PlayerLeft,
          Json.obj("reason" -> "EATEN".asJson, "eatenBy" -> actor.asJson)
        )
      yield Some(
        (
          List(eaten, left),
          s.copy(masses = (s.masses - victim).updated(actor, total), clock = at, forced = None, tick = s.tick + 1)
        )
      )

  private def step(spec: MatchSpec)(s: State): Gen[Option[(List[Envelope], State)]] =
    if s.masses.size <= 1 || s.tick >= MaxTicks then Gen.pure(None)
    else
      s.forced match
        case Some((actor, delay)) if s.masses.contains(actor) => doEat(spec, s, actor, delay)
        case _ =>
          for
            actor <- Gen.pick(s.masses.keys.toList.sorted)
            wantsSplit <- Gen.chance(0.35)
            out <-
              if wantsSplit && s.masses(actor) >= 60 then doSplit(spec, s, actor)
              else Gen.between(1500, 5000).flatMap(gap => doEat(spec, s, actor, gap.toLong))
          yield out

  def generate(spec: MatchSpec): Gen[List[Envelope]] =
    for
      count   <- Gen.between(3, 5)
      ids      = (1 to count).map(i => s"usr_$i").toList
      masses  <- Gen.sequence(ids.map(_ => Gen.between(100, 400).map(_.toDouble)))
      joined  <- spec.joined(ids)
      begin   <- spec.started(ids)
      played  <- Gen.unfold[State, Envelope](State(ids.zip(masses).toMap, spec.start, None, 0))(step(spec))
      last     = played._2
      winner   = last.masses.maxBy(_._2)._1
      end     <- spec.finished(
        last.clock.plusMillis(1000),
        Json.obj("winnerId" -> winner.asJson, "finalMass" -> last.masses(winner).asJson)
      )
    yield joined ++ (begin :: played._1) ++ List(end)
