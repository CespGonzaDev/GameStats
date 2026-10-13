package gamestats.analytics

import gamestats.TestKit.*
import gamestats.model.MatchReport
import org.scalatest.funsuite.AnyFunSuite

class SnakeAgarioSpec extends AnyFunSuite:

  private def player(report: MatchReport, id: String) = report.players.find(_.playerId == id).get

  // ------------------------------------------------------------------------------------ Snake
  private def snake(specs: List[Spec]): MatchReport = MatchAnalysis.analyze(events("snake", "s1", specs)).head

  test("Snake: giros entre comidas sucesivas (eficiencia de movimiento)") {
    val report = snake(
      List(
        started(),
        turn(500),               // antes de la primera comida: no pertenece a ningún intervalo
        food(1000),
        turn(1500), turn(2000),  // intervalo 1: 2 giros
        food(2500),
        turn(3000),              // intervalo 2: 1 giro
        food(3500),
        turn(4000),              // tramo final sin comida: no cuenta
        collision(4500, "p1", 50),
        finished(5000)
      )
    )
    val p1 = player(report, "p1")
    assert(p1.crossEventMetrics("foodIntervals") == 2)
    assert(p1.crossEventMetrics("avgDirectionChangesBetweenFood") == 1.5)
    assert(p1.crossEventMetrics("bestDirectionChangesBetweenFood") == 1)
    assert(p1.metrics("directionChanges") == 5)
  }

  test("Snake: puntuación máxima y distribución de comida") {
    val report = snake(
      List(started(), food(1000, points = 10), food(2000, points = 10, length = 5), collision(3000, "p1", 50), finished(4000))
    )
    val p1 = player(report, "p1")
    assert(p1.metrics("totalPoints") == 20)
    assert(p1.metrics("peakScore") == 50) // el finalScore declarado supera los puntos acumulados
    assert(p1.metrics("maxLength") == 5)
    assert(p1.distributions("foodTypes") == Map("APPLE" -> 2))
    assert(report.matchMetrics("peakScore") == 50)
  }

  test("Snake: un jugador con una sola comida no tiene intervalos") {
    val p1 = player(snake(List(started(), food(1000), finished(2000))), "p1")
    assert(p1.crossEventMetrics("foodIntervals") == 0)
    assert(!p1.crossEventMetrics.contains("avgDirectionChangesBetweenFood"))
  }

  // ----------------------------------------------------------------------------------- Agar.io
  private def agar(specs: List[Spec]): MatchReport = MatchAnalysis.analyze(events("agario", "a1", specs)).head

  test("Agar.io: factor de conversión de riesgo con límite de 10 s inclusivo") {
    val report = agar(
      List(
        started(),
        split(10000, "usr_1", 400.0),
        eat(14000, "usr_1", "usr_2", 100.0, 500.0),  // +4 s: dentro
        eat(20000, "usr_1", "usr_3", 50.0, 550.0),   // +10 s exactos: dentro (límite inclusivo)
        eat(20001, "usr_1", "usr_4", 70.0, 620.0),   // +10,001 s: fuera
        split(60000, "usr_1", 620.0),                // segundo split sin comidas
        finished(90000)
      )
    )
    val p = player(report, "usr_1")
    assert(p.metrics("splits") == 2 && p.metrics("kills") == 3)
    assert(p.metrics("massConsumed") == 220)
    assert(p.crossEventMetrics("massGainedWithin10sOfSplit") == 150)
    assert(p.crossEventMetrics("splitsWithKill") == 1)
    assert(p.crossEventMetrics("splitSuccessRate") == 0.5)
    assert(p.crossEventMetrics("riskConversionFactor") == 0.1471) // 150 / (400 + 620)
    assert(p.metrics("peakMass") == 620)
    assert(report.matchMetrics("peakMass") == 620 && report.matchMetrics("totalKills") == 3)
  }

  test("Agar.io: cada EAT_PLAYER se atribuye a un solo SPLIT (el más reciente)") {
    val report = agar(
      List(
        started(),
        split(0, "usr_1", 200.0),
        split(5000, "usr_1", 300.0),
        eat(8000, "usr_1", "usr_2", 100.0, 400.0),
        finished(20000)
      )
    )
    val p = player(report, "usr_1")
    assert(p.crossEventMetrics("massGainedWithin10sOfSplit") == 100) // no se duplica por estar en dos ventanas
    assert(p.crossEventMetrics("splitsWithKill") == 1)
    assert(p.crossEventMetrics("riskConversionFactor") == 0.2) // 100 / (200 + 300)
  }

  test("Agar.io: comer sin SPLIT previo no cuenta como conversión de riesgo") {
    val report = agar(List(started(), eat(1000, "usr_1", "usr_2", 80.0, 180.0), finished(2000)))
    val p      = player(report, "usr_1")
    assert(p.crossEventMetrics("massGainedWithin10sOfSplit") == 0)
    assert(!p.crossEventMetrics.contains("riskConversionFactor")) // sin splits no hay masa arriesgada
  }

  test("Agar.io: los splits de un jugador no se atribuyen a las comidas de otro") {
    val report = agar(
      List(started(), split(0, "usr_1", 200.0), eat(2000, "usr_2", "usr_3", 60.0, 260.0), finished(5000))
    )
    assert(player(report, "usr_2").crossEventMetrics("massGainedWithin10sOfSplit") == 0)
  }
