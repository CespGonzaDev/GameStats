package gamestats.analytics

import gamestats.TestKit.*
import gamestats.model.Envelope
import org.scalatest.funsuite.AnyFunSuite

class MatchAnalysisSpec extends AnyFunSuite:

  /** Partida de un juego sin analizador: 8 jugadas, 120 s de duración -> 10 eventos en total. */
  private def chess(matchId: String = "c1"): List[Envelope] =
    events(
      "ajedrez",
      matchId,
      started(0) :: List.tabulate(8)(i => Spec(10000L * (i + 1), if i % 2 == 0 then "white" else "black", "MOVE_PIECE")) :::
        List(finished(120000))
    )

  test("duración efectiva e intensidad de eventos por minuto (globales)") {
    val r = MatchAnalysis.analyze(chess()).head
    assert(r.status == "FINISHED")
    assert(r.matchMetrics("durationSeconds") == 120)
    assert(r.matchMetrics("eventsPerMinute") == 5) // 10 eventos / 2 minutos
    assert(r.eventCount == 10)
    assert(r.actionCounts("MOVE_PIECE") == 8)
  }

  test("un juego nuevo sin analizador se procesa con métricas globales y una advertencia") {
    val r = MatchAnalysis.analyze(chess()).head
    assert(r.warnings.exists(_.contains("sin analizador específico")))
    assert(r.players.map(_.playerId).toSet == Set("white", "black"))
    assert(r.players.find(_.playerId == "white").get.metrics("eventCount") == 4)
  }

  test("extensibilidad: registrar un analizador nuevo no requiere tocar el núcleo") {
    object Chess extends GameAnalyzer:
      val gameId = "ajedrez"
      def analyze(ctx: MatchContext): GameAnalysis =
        GameAnalysis(matchMetrics = Map("moves" -> ctx.events.count(_.action == "MOVE_PIECE").toDouble))

    val registry = GameRegistry.default.register(Chess)
    val r        = MatchAnalysis.analyze(chess(), registry).head
    assert(r.matchMetrics("moves") == 8)
    assert(!r.warnings.exists(_.contains("sin analizador")))
    // y los juegos ya existentes siguen intactos
    assert(registry.find("battleship").isDefined && registry.find("SNAKE").isDefined)
  }

  test("eventos duplicados (mismo eventId) se descartan y se cuentan") {
    val base = chess()
    val r    = MatchAnalysis.analyze(base ++ base.take(3)).head
    assert(r.eventCount == 10 && r.duplicatesDropped == 3)
  }

  test("el orden de llegada no importa: el resultado es el mismo con eventos desordenados") {
    val ordered = chess()
    assert(MatchAnalysis.analyze(ordered.reverse) == MatchAnalysis.analyze(ordered))
  }

  test("partida sin MATCH_FINISHED: IN_PROGRESS, sin duración efectiva, con advertencia") {
    val r = MatchAnalysis.analyze(events("ajedrez", "c1", List(started(), Spec(30000, "white", "MOVE_PIECE")))).head
    assert(r.status == "IN_PROGRESS")
    assert(!r.matchMetrics.contains("durationSeconds"))
    assert(r.matchMetrics("eventsPerMinute") == 4) // 2 eventos en 30 s de lapso
    assert(r.warnings.exists(_.contains("MATCH_FINISHED")))
  }

  test("partida sin MATCH_STARTED: estado desconocido y advertencia") {
    val r = MatchAnalysis.analyze(events("ajedrez", "c1", List(Spec(1000, "white", "MOVE_PIECE"), finished(5000)))).head
    assert(r.status == "UNKNOWN")
    assert(r.warnings.exists(_.contains("MATCH_STARTED")))
  }

  test("varias partidas se agrupan por matchId y salen ordenadas por inicio") {
    val late  = events("ajedrez", "late", started(60000) :: List(finished(70000))).map(e => e)
    val early = chess("early")
    val reports = MatchAnalysis.analyze(late ++ early)
    assert(reports.map(_.matchId) == List("early", "late"))
  }

  test("una partida que mezcla gameId distintos genera advertencia") {
    val mixed = chess().updated(3, chess().apply(3).copy(gameId = "otro"))
    assert(MatchAnalysis.analyze(mixed).head.warnings.exists(_.contains("mezcla gameId")))
  }
