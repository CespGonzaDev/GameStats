package gamestats.analytics

import gamestats.TestKit.*
import gamestats.model.{MatchReport, PatternDetection}
import org.scalatest.funsuite.AnyFunSuite

class BattleshipSpec extends AnyFunSuite:

  private def analyze(specs: List[Spec]): MatchReport =
    MatchAnalysis.analyze(events("battleship", "m1", specs)).head

  private def streaks(report: MatchReport): List[PatternDetection] =
    report.patterns.filter(_.pattern == "DEADLY_STREAK")

  private def player(report: MatchReport, id: String) = report.players.find(_.playerId == id).get

  test("precisión global y tiempo medio de decisión por turno") {
    val report = analyze(
      List(
        started(0),
        shot(5000, "p1", "HIT"),
        shot(8000, "p2", "MISS"),
        shot(15000, "p1", "MISS"),
        shot(16000, "p2", "MISS"),
        shot(21000, "p1", "HIT"),
        shot(30000, "p2", "HIT"),
        finished(31000)
      )
    )
    val p1 = player(report, "p1")
    val p2 = player(report, "p2")
    assert(p1.metrics("shots") == 3 && p1.metrics("hits") == 2)
    assert(p1.metrics("accuracy") == 0.6667)
    assert(p2.metrics("accuracy") == 0.3333)
    // p1: 5 s (desde el inicio), 7 s (tras el disparo de p2 a los 8 s) y 5 s -> 5.6667
    assert(p1.metrics("avgDecisionSeconds") == 5.6667)
    // p2: 3 s, 1 s y 9 s -> 4.3333
    assert(p2.metrics("avgDecisionSeconds") == 4.3333)
    assert(p1.distributions("results") == Map("HIT" -> 2, "MISS" -> 1))
  }

  test("racha crítica: 3 impactos seguidos que terminan hundiendo un barco") {
    val report = analyze(
      List(
        started(),
        shot(1000, "p1", "HIT", turn = 1),
        shot(2000, "p1", "HIT", turn = 2),
        shot(3000, "p1", "HIT", Some("CRUISER"), turn = 3),
        finished(4000)
      )
    )
    val found = streaks(report)
    assert(found.size == 1)
    assert(found.head.playerId == "p1")
    assert(found.head.details("streakLength") == 3)
    assert(found.head.details("startTurn") == 1 && found.head.details("endTurn") == 3)
    assert(found.head.eventIds.size == 3)
  }

  test("2 impactos y hundimiento no alcanzan la racha mínima") {
    val report = analyze(List(started(), shot(1000, "p1", "HIT"), shot(2000, "p1", "HIT", Some("DESTROYER"))))
    assert(streaks(report).isEmpty)
  }

  test("un fallo intermedio interrumpe la racha") {
    val report = analyze(
      List(
        started(),
        shot(1000, "p1", "HIT"),
        shot(2000, "p1", "HIT"),
        shot(3000, "p1", "MISS"),
        shot(4000, "p1", "HIT", Some("DESTROYER"))
      )
    )
    assert(streaks(report).isEmpty)
  }

  test("3 impactos sin hundimiento no son racha crítica") {
    val report = analyze(List(started(), shot(1000, "p1", "HIT"), shot(2000, "p1", "HIT"), shot(3000, "p1", "HIT")))
    assert(streaks(report).isEmpty)
  }

  test("los disparos del rival no interrumpen la racha del jugador") {
    val report = analyze(
      List(
        started(),
        shot(1000, "p1", "HIT"),
        shot(1500, "p2", "MISS"),
        shot(2000, "p1", "HIT"),
        shot(2500, "p2", "HIT"),
        shot(3000, "p1", "HIT", Some("CRUISER"))
      )
    )
    assert(streaks(report).map(_.playerId) == List("p1"))
  }

  test("la racha consumida no se cuenta dos veces") {
    // HIT HIT HIT(hunde) -> detección; luego HIT HIT(hunde) solo suma 2 impactos nuevos -> no detecta
    val report = analyze(
      List(
        started(),
        shot(1000, "p1", "HIT"),
        shot(2000, "p1", "HIT"),
        shot(3000, "p1", "HIT", Some("CRUISER")),
        shot(4000, "p1", "HIT"),
        shot(5000, "p1", "HIT", Some("DESTROYER"))
      )
    )
    assert(streaks(report).size == 1)
  }

  test("un hundimiento cierra la racha: los impactos previos no se arrastran al siguiente barco") {
    // HIT HIT(hunde DESTROYER) | HIT HIT HIT(hunde CRUISER): la segunda racha es de 3, no de 5
    val report = analyze(
      List(
        started(),
        shot(1000, "p1", "HIT", turn = 1),
        shot(2000, "p1", "HIT", Some("DESTROYER"), turn = 2),
        shot(3000, "p1", "HIT", turn = 3),
        shot(4000, "p1", "HIT", turn = 4),
        shot(5000, "p1", "HIT", Some("CRUISER"), turn = 5)
      )
    )
    val found = streaks(report)
    assert(found.size == 1)
    assert(found.head.details("streakLength") == 3 && found.head.details("startTurn") == 3)
  }

  test("una racha larga reporta su longitud completa") {
    val report = analyze(
      started() :: List.tabulate(4)(i => shot(1000L * (i + 1), "p1", "HIT")) :::
        List(shot(5000, "p1", "HIT", Some("CARRIER")))
    )
    assert(streaks(report).head.details("streakLength") == 5)
  }

  test("un resultado desconocido genera advertencia y no rompe el análisis") {
    val report = analyze(List(started(), shot(1000, "p1", "BOOM"), shot(2000, "p1", "HIT")))
    assert(report.warnings.exists(_.contains("payload inválido")))
    assert(player(report, "p1").metrics("shots") == 1)
  }
