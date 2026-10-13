package gamestats.sim

import gamestats.model.{Actions, Envelope, EventTypes}
import io.circe.Json
import io.circe.syntax.*

import java.time.Instant

/** Partidas guionizadas con resultados conocidos, para probar reglas complejas y casos borde.
  *
  * Cada escenario tiene `matchId` fijo (prefijo `edge-`) y su resultado esperado documentado,
  * de modo que sirven tanto de demo como de prueba de integración.
  */
object EdgeCases:

  val Base: Instant = Instant.parse("2026-10-07T19:30:00Z")

  /** Todos los escenarios, cada uno en su propia partida. */
  def all: Gen[List[Envelope]] =
    Gen.sequence(List(battleshipStreak, comebackWin, leadThrownAway, incompleteMatch, agarioWindow, snakeRoute, unknownGame))
      .map(_.flatten)

  // ---------------------------------------------------------------------------------------------
  // Battleship: p1 dispara M H H H(sunk) H H M H H H H(sunk); p2 falla siempre.
  // Esperado: 2 DEADLY_STREAK de p1 (longitudes 3 y 4); la racha de 2 impactos seguida de MISS no cuenta.
  // ---------------------------------------------------------------------------------------------
  private final case class Script(result: String, sunk: Option[String] = None)

  def battleshipStreak: Gen[List[Envelope]] =
    val spec = MatchSpec("battleship", "edge-battleship-streak", Base)
    val p1 = List(
      Script("MISS"), Script("HIT"), Script("HIT"), Script("HIT", Some("CRUISER")),
      Script("HIT"), Script("HIT"), Script("MISS"),
      Script("HIT"), Script("HIT"), Script("HIT"), Script("HIT", Some("BATTLESHIP"))
    )
    val shots = p1.zipWithIndex.flatMap((s, i) => List(("p1", s, i), ("p2", Script("MISS"), i)))
    for
      joined <- spec.joined(List("p1", "p2"))
      begin  <- spec.started(List("p1", "p2"))
      events <- Gen.sequence(shots.zipWithIndex.map { case ((player, script, i), n) =>
        spec.event(
          spec.at(3000L * (n + 1)),
          player,
          EventTypes.Action,
          "FIRE_SHOT",
          Json.obj(
            "coord" -> Json.obj("row" -> BattleshipSim.RowNames(i % 10).toString.asJson, "col" -> (i % 10 + 1).asJson),
            "turn" -> (n + 1).asJson,
            "result" -> script.result.asJson,
            "shipSunk" -> script.sunk.fold(Json.Null)(_.asJson)
          )
        )
      })
      end <- spec.finished(spec.at(3000L * (shots.size + 1)), Json.obj("winnerId" -> "p1".asJson))
    yield joined ++ (begin :: events) ++ List(end)

  // ---------------------------------------------------------------------------------------------
  // Head Football (scripts de goles). Cada gol va precedido de su KICK y de un KICK fallido.
  // ---------------------------------------------------------------------------------------------
  private def footballMatch(matchId: String, goals: List[String], finish: Boolean): Gen[List[Envelope]] =
    val spec  = MatchSpec("head_football", matchId, Base)
    val ids   = List("p1", "p2")
    val types = List("GROUND", "HEADER", "LOB")
    val scores = goals.scanLeft(Map.empty[String, Int])((acc, g) => acc.updated(g, acc.getOrElse(g, 0) + 1)).tail
    for
      joined <- spec.joined(ids)
      begin  <- spec.started(ids)
      plays <- Gen.sequence(goals.zip(scores).zipWithIndex.map { case ((scorer, score), i) =>
        val minute = 10 * (i + 1)
        val base   = 10000L * (i + 1)
        for
          miss <- spec.event(
            spec.at(base - 3000),
            scorer,
            EventTypes.Action,
            "KICK",
            Json.obj("shotType" -> "GROUND".asJson, "ballSpeed" -> 40.5.asJson, "power" -> 0.4.asJson)
          )
          kick <- spec.event(
            spec.at(base),
            scorer,
            EventTypes.Action,
            "KICK",
            Json.obj("shotType" -> types(i % 3).asJson, "ballSpeed" -> 68.5.asJson, "power" -> 0.85.asJson)
          )
          goal <- spec.event(
            spec.at(base + 800),
            scorer,
            EventTypes.Interaction,
            "GOAL",
            Json.obj(
              "scorerId"     -> scorer.asJson,
              "minute"       -> minute.asJson,
              "currentScore" -> HeadFootballSim.scoreJson(ids, score)
            )
          )
        yield List(miss, kick, goal)
      })
      end <- spec.finished(
        spec.at(10000L * (goals.size + 1)),
        Json.obj("finalScore" -> HeadFootballSim.scoreJson(ids, scores.lastOption.getOrElse(Map.empty)))
      )
    yield joined ++ (begin :: plays.flatten) ++ (if finish then List(end) else Nil)

  /** p1 2-0 arriba, p2 marca tres: gana 2-3. Esperado: COMEBACK de p2 (desventaja máxima 2). */
  def comebackWin: Gen[List[Envelope]] =
    footballMatch("edge-football-comeback", List("p1", "p1", "p2", "p2", "p2"), finish = true)

  /** p2 llega a estar 0-2 pero solo recorta (1-2) y pierde. Esperado: sin COMEBACK. */
  def leadThrownAway: Gen[List[Envelope]] =
    footballMatch("edge-football-no-comeback", List("p1", "p1", "p2"), finish = true)

  /** Misma secuencia de la remontada pero sin MATCH_FINISHED. Esperado: sin COMEBACK y status IN_PROGRESS. */
  def incompleteMatch: Gen[List[Envelope]] =
    footballMatch("edge-football-incomplete", List("p1", "p1", "p2", "p2", "p2"), finish = false)

  // ---------------------------------------------------------------------------------------------
  // Agar.io: usr_1 se divide en t=10 s y come a +4 s (100), +10 s exactos (50) y +10,5 s (70).
  // Esperado: masa convertida dentro de la ventana = 150; el último EAT_PLAYER queda fuera.
  // Un segundo SPLIT (t=60 s) sin comidas: splits=2, splitsWithKill=1.
  // ---------------------------------------------------------------------------------------------
  def agarioWindow: Gen[List[Envelope]] =
    val spec = MatchSpec("agario", "edge-agario-window", Base)
    val ids  = List("usr_1", "usr_2", "usr_3", "usr_4")
    def split(offsetMs: Long, mass: Double) =
      spec.event(
        spec.at(offsetMs),
        "usr_1",
        EventTypes.Interaction,
        "SPLIT",
        Json.obj("massBefore" -> mass.asJson, "cellCount" -> 2.asJson)
      )
    def eat(offsetMs: Long, victim: String, gained: Double, total: Double) =
      spec.event(
        spec.at(offsetMs),
        "usr_1",
        EventTypes.Interaction,
        "EAT_PLAYER",
        Json.obj("victimId" -> victim.asJson, "massGained" -> gained.asJson, "totalMass" -> total.asJson)
      )
    for
      joined <- spec.joined(ids)
      begin  <- spec.started(ids)
      plays <- Gen.sequence(
        List(
          split(10000, 400.0),
          eat(14000, "usr_2", 100.0, 500.0),
          eat(20000, "usr_3", 50.0, 550.0),
          eat(20500, "usr_4", 70.0, 620.0),
          split(60000, 620.0)
        )
      )
      end <- spec.finished(spec.at(90000), Json.obj("winnerId" -> "usr_1".asJson))
    yield joined ++ (begin :: plays) ++ List(end)

  // ---------------------------------------------------------------------------------------------
  // Snake: tres comidas -> dos intervalos, con 3 y 1 giros -> promedio 2.
  // El giro previo a la primera comida y los 2 giros finales (que terminan en colisión, no en
  // comida) no pertenecen a ningún intervalo.
  // ---------------------------------------------------------------------------------------------
  def snakeRoute: Gen[List[Envelope]] =
    val spec = MatchSpec("snake", "edge-snake-route", Base)
    def turn(offsetMs: Long) =
      spec.event(
        spec.at(offsetMs),
        "p1",
        EventTypes.Input,
        "CHANGE_DIRECTION",
        Json.obj("from" -> "UP".asJson, "to" -> "LEFT".asJson)
      )
    def food(offsetMs: Long, length: Int) =
      spec.event(
        spec.at(offsetMs),
        "p1",
        EventTypes.Action,
        "EAT_FOOD",
        Json.obj(
          "foodType"      -> "APPLE".asJson,
          "points"        -> 10.asJson,
          "currentLength" -> length.asJson,
          "coords"        -> Json.obj("x" -> 5.asJson, "y" -> 8.asJson)
        )
      )
    for
      joined <- spec.joined(List("p1"))
      begin  <- spec.started(List("p1"))
      plays <- Gen.sequence(
        List(
          turn(500), food(1000, 4),                    // antes de la primera comida: no cuenta
          turn(1500), turn(2000), turn(2500), food(3000, 5), // intervalo 1: 3 giros
          turn(3500), food(4000, 6),                   // intervalo 2: 1 giro
          turn(4500), turn(5000)                       // tramo final sin comida
        )
      )
      collision <- spec.event(
        spec.at(5500),
        "p1",
        EventTypes.Interaction,
        "COLLISION",
        Json.obj("collisionWith" -> "SELF".asJson, "finalScore" -> 30.asJson)
      )
      end <- spec.finished(spec.at(6000), Json.obj("winnerId" -> "p1".asJson))
    yield joined ++ (begin :: plays) ++ List(collision, end)

  // ---------------------------------------------------------------------------------------------
  // Juego nuevo sin analizador (ajedrez): demuestra la extensibilidad del dominio.
  // Esperado: se procesa con métricas globales y una advertencia, sin modificar el núcleo.
  // ---------------------------------------------------------------------------------------------
  def unknownGame: Gen[List[Envelope]] =
    val spec = MatchSpec("ajedrez", "edge-new-game-chess", Base)
    def move(offsetMs: Long, player: String, san: String) =
      spec.event(
        spec.at(offsetMs),
        player,
        EventTypes.Action,
        "MOVE_PIECE",
        Json.obj("san" -> san.asJson, "clockMs" -> 600000.asJson)
      )
    for
      joined <- spec.joined(List("white", "black"))
      begin  <- spec.started(List("white", "black"))
      moves  <- Gen.sequence(List(move(5000, "white", "e4"), move(9000, "black", "e5"), move(15000, "white", "Nf3")))
      end    <- spec.finished(spec.at(20000), Json.obj("result" -> "1-0".asJson))
    yield joined ++ (begin :: moves) ++ List(end)
