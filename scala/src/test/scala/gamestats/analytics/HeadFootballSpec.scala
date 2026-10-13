package gamestats.analytics

import gamestats.TestKit.*
import gamestats.model.MatchReport
import org.scalatest.funsuite.AnyFunSuite

class HeadFootballSpec extends AnyFunSuite:

  /** Partido con los goles dados en orden; cada gol se anota 1 s después de un KICK. */
  private def match_(goals: List[String], finish: Boolean = true): MatchReport =
    val plays = goals.zipWithIndex.flatMap((scorer, i) =>
      List(kick(10000L * (i + 1), scorer, "GROUND"), goal(10000L * (i + 1) + 1000, scorer))
    )
    val closing = if finish then List(finished(10000L * (goals.size + 1))) else Nil
    MatchAnalysis.analyze(events("head_football", "m1", started() :: plays ::: closing)).head

  private def comebacks(r: MatchReport) = r.patterns.filter(_.pattern == "COMEBACK")

  test("efectividad de gol por tiro y distribución de tipos de disparo") {
    val report = MatchAnalysis
      .analyze(
        events(
          "head_football",
          "m1",
          List(
            started(),
            kick(1000, "p1", "GROUND"),
            kick(2000, "p1", "GROUND"),
            kick(3000, "p1", "HEADER"),
            kick(4000, "p1", "LOB"),
            goal(4500, "p1"),
            kick(5000, "p2", "LOB"),
            goal(5500, "p2"),
            kick(6000, "p2", "HEADER"),
            goal(6500, "p2"),
            finished(7000)
          )
        )
      )
      .head
    val p1 = report.players.find(_.playerId == "p1").get
    val p2 = report.players.find(_.playerId == "p2").get
    assert(p1.metrics("shots") == 4 && p1.metrics("goals") == 1)
    assert(p1.metrics("shotEffectiveness") == 0.25)
    assert(p1.distributions("shotTypes") == Map("GROUND" -> 2, "HEADER" -> 1, "LOB" -> 1))
    assert(p2.metrics("shotEffectiveness") == 1)
    assert(report.matchMetrics("totalGoals") == 3 && report.matchMetrics("totalShots") == 6)
  }

  test("remontada épica: va 0-2 abajo y termina ganando 3-2") {
    val found = comebacks(match_(List("p1", "p1", "p2", "p2", "p2")))
    assert(found.size == 1)
    val c = found.head
    assert(c.playerId == "p2")
    assert(c.details("maxDeficit") == 2)
    assert(c.details("winnerFinalGoals") == 3 && c.details("rivalFinalGoals") == 2)
    assert(c.details("goalsAfterDeficit") == 3)
    assert(c.eventIds.size == 4) // el gol que abre la desventaja + los 3 posteriores
  }

  test("desventaja de 2 sin ganar no es remontada") {
    assert(comebacks(match_(List("p1", "p1", "p2"))).isEmpty)
  }

  test("empatar tras ir 0-2 no es remontada (no hay victoria)") {
    assert(comebacks(match_(List("p1", "p1", "p2", "p2"))).isEmpty)
  }

  test("ganar tras una desventaja de solo 1 gol no es remontada épica") {
    assert(comebacks(match_(List("p1", "p2", "p2", "p2"))).isEmpty)
  }

  test("sin MATCH_FINISHED no se declara remontada") {
    val report = match_(List("p1", "p1", "p2", "p2", "p2"), finish = false)
    assert(comebacks(report).isEmpty)
    assert(report.status == "IN_PROGRESS")
  }

  test("la remontada también cuenta si la desventaja ocurre a mitad de partido") {
    // p1 1-0, p2 1-1, p1 2-1, p1 3-1 (p2 abajo por 2), p2 3-2, p2 3-3, p2 3-4
    val found = comebacks(match_(List("p1", "p2", "p1", "p1", "p2", "p2", "p2")))
    assert(found.map(_.playerId) == List("p2"))
    assert(found.head.details("maxDeficit") == 2)
  }

  test("el marcador se recalcula; un currentScore inconsistente solo genera advertencia") {
    val report = MatchAnalysis
      .analyze(
        events(
          "head_football",
          "m1",
          List(started(), kick(1000, "p1", "LOB"), goal(2000, "p1", Some(Map("p1" -> 5))), finished(3000))
        )
      )
      .head
    assert(report.warnings.exists(_.contains("no coincide")))
    assert(report.matchMetrics("totalGoals") == 1)
  }
