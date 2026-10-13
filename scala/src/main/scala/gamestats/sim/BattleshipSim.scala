package gamestats.sim

import gamestats.model.{Envelope, EventTypes}
import io.circe.Json
import io.circe.syntax.*

import java.time.Instant
import scala.annotation.tailrec

/** Simula una partida de Battleship (10x10) entre dos bots.
  *
  * Los bots usan estrategia "caza/objetivo": disparan en patrón de tablero de ajedrez y, tras un
  * impacto, prueban las casillas vecinas. Eso produce rachas de impactos como las de un jugador real.
  */
object BattleshipSim:

  type Cell = (Int, Int) // (fila 0..9, columna 1..10)
  type Fleet = Map[String, Set[Cell]]

  val RowNames: String = "ABCDEFGHIJ"

  private val Ships = List("CARRIER" -> 5, "BATTLESHIP" -> 4, "CRUISER" -> 3, "SUBMARINE" -> 3, "DESTROYER" -> 2)
  private val AllCells: List[Cell] = (for r <- 0 until 10; c <- 1 to 10 yield (r, c)).toList

  private final case class Shooter(shot: Set[Cell], pending: List[Cell])
  private final case class State(
      turn: Int,
      current: Int,
      shooters: Vector[Shooter],
      fleets: Vector[Fleet],
      clock: Instant,
      over: Boolean
  )

  private def placeFleet: Gen[Fleet] =
    Gen { rng0 =>
      Ships.foldLeft((Map.empty[String, Set[Cell]], rng0)) { case ((placed, rng), (name, length)) =>
        val occupied = placed.values.flatten.toSet

        @tailrec def attempt(r: Rng): (Set[Cell], Rng) =
          val (horizontal, r1) = r.nextInt(2)
          val (row, r2)        = r1.nextInt(10)
          val (colOffset, r3)  = r2.nextInt(10)
          val col              = colOffset + 1
          val cells = (0 until length).map(i => if horizontal == 0 then (row, col + i) else (row + i, col)).toSet
          if cells.forall((r, c) => r < 10 && c <= 10 && !occupied((r, c))) then (cells, r3) else attempt(r3)

        val (cells, next) = attempt(rng)
        (placed.updated(name, cells), next)
      }
    }

  private def target(me: Shooter): Gen[Cell] =
    me.pending.filterNot(me.shot).headOption match
      case Some(cell) => Gen.pure(cell)
      case None =>
        val free   = AllCells.filterNot(me.shot)
        val parity = free.filter((r, c) => (r + c) % 2 == 0)
        Gen.pick(if parity.nonEmpty then parity else free)

  private def neighbors(cell: Cell): List[Cell] =
    val (r, c) = cell
    List((r - 1, c), (r + 1, c), (r, c - 1), (r, c + 1)).filter((nr, nc) => nr >= 0 && nr < 10 && nc >= 1 && nc <= 10)

  private def step(spec: MatchSpec, ids: Vector[String])(s: State): Gen[Option[(List[Envelope], State)]] =
    if s.over then Gen.pure(None)
    else
      val me         = s.shooters(s.current)
      val enemyFleet = s.fleets(1 - s.current)
      for
        cell <- target(me)
        gap  <- Gen.between(2000, 9000)
        at        = s.clock.plusMillis(gap)
        shotAfter = me.shot + cell
        hitShip   = enemyFleet.collectFirst { case (name, cells) if cells(cell) => name -> cells }
        sunk      = hitShip.collect { case (name, cells) if cells.subsetOf(shotAfter) => name }
        data = Json.obj(
          "coord"    -> Json.obj("row" -> RowNames(cell._1).toString.asJson, "col" -> cell._2.asJson),
          "turn"     -> s.turn.asJson,
          "result"   -> (if hitShip.isDefined then "HIT" else "MISS").asJson,
          "shipSunk" -> sunk.fold(Json.Null)(_.asJson)
        )
        event <- spec.event(at, ids(s.current), EventTypes.Action, "FIRE_SHOT", data)
      yield
        val pending =
          if hitShip.isEmpty then me.pending.filterNot(shotAfter)
          else if sunk.isDefined then Nil
          else (neighbors(cell).filterNot(shotAfter) ++ me.pending).filterNot(shotAfter).distinct
        val over = enemyFleet.values.flatten.forall(shotAfter)
        Some(
          (
            List(event),
            s.copy(
              turn = s.turn + 1,
              current = if over then s.current else 1 - s.current,
              shooters = s.shooters.updated(s.current, Shooter(shotAfter, pending)),
              clock = at,
              over = over
            )
          )
        )

  def generate(spec: MatchSpec): Gen[List[Envelope]] =
    val ids = Vector("p1", "p2")
    for
      fleet1 <- placeFleet
      fleet2 <- placeFleet
      joined <- spec.joined(ids.toList)
      begin  <- spec.started(ids.toList)
      played <- Gen.unfold[State, Envelope](
        State(1, 0, Vector(Shooter(Set.empty, Nil), Shooter(Set.empty, Nil)), Vector(fleet1, fleet2), spec.start, over = false)
      )(step(spec, ids))
      last = played._2
      end <- spec.finished(
        last.clock.plusMillis(1500),
        Json.obj("winnerId" -> ids(last.current).asJson, "totalTurns" -> (last.turn - 1).asJson)
      )
    yield joined ++ (begin :: played._1) ++ List(end)
