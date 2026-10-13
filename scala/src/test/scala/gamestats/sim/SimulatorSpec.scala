package gamestats.sim

import gamestats.analytics.MatchAnalysis
import gamestats.report.ReportJson
import gamestats.model.MatchReport
import gamestats.parsing.{EnvelopeCodec, EventParser}
import io.circe.parser.parse
import org.scalatest.funsuite.AnyFunSuite

class SimulatorSpec extends AnyFunSuite:

  private val config = SimConfig(matchesPerGame = 2, seed = 7L)

  private def report(reports: List[MatchReport], id: String): MatchReport = reports.find(_.matchId == id).get

  test("es determinista: misma semilla, mismos eventos; otra semilla, otros eventos") {
    assert(Simulator.generate(config) == Simulator.generate(config))
    assert(Simulator.generate(config) != Simulator.generate(config.copy(seed = 8L)))
  }

  test("genera partidas completas y eventos con ids únicos de los cuatro juegos") {
    val events  = Simulator.generate(config)
    val reports = MatchAnalysis.analyze(events)
    assert(reports.size == 8)
    assert(events.map(_.eventId).distinct.size == events.size)
    assert(reports.map(_.gameId).toSet == Set("snake", "agario", "battleship", "head_football"))
    assert(reports.forall(_.status == "FINISHED"))
    assert(reports.forall(_.players.nonEmpty))
    // sin juegos desconocidos no debería haber advertencias de datos
    assert(reports.flatMap(_.warnings).isEmpty, reports.flatMap(_.warnings).mkString("; "))
  }

  test("los eventos serializados pasan la validación del Envelope (ida y vuelta)") {
    val events = Simulator.generate(config)
    val parsed = EventParser.parseInput(EnvelopeCodec.toNdjson(events))
    assert(parsed.invalid.isEmpty)
    assert(parsed.events == events)
    assert(EventParser.parseInput(EnvelopeCodec.toJsonArray(events)).events == events)
  }

  test("Battleship simulado: cada partida termina con una flota hundida") {
    val events  = Simulator.generate(SimConfig(games = List("battleship"), matchesPerGame = 3, seed = 3L))
    val reports = MatchAnalysis.analyze(events)
    reports.foreach { r =>
      val sunk = r.players.map(_.metrics.getOrElse("shipsSunk", 0.0)).max
      assert(sunk == 5, s"${r.matchId}: se esperaban 5 barcos hundidos por el ganador")
    }
  }

  test("las partidas simuladas se intercalan en el tiempo (concurrencia entre partidas)") {
    val reports = MatchAnalysis.analyze(Simulator.generate(config))
    val spans   = reports.flatMap(r => r.startedAt.zip(r.finishedAt))
    val overlapping = for
      (a, i) <- spans.zipWithIndex
      (b, j) <- spans.zipWithIndex
      if i < j && a._1.isBefore(b._2) && b._1.isBefore(a._2)
    yield (i, j)
    assert(overlapping.nonEmpty)
  }

  test("desorden y duplicados no cambian el análisis (solo el contador de duplicados)") {
    val clean = Simulator.generate(SimConfig(matchesPerGame = 2, seed = 11L, edgeCases = true))
    val noisy = Simulator.generate(SimConfig(matchesPerGame = 2, seed = 11L, edgeCases = true, shuffle = true, duplicates = true))
    assert(noisy.size > clean.size)
    val a = MatchAnalysis.analyze(clean)
    val b = MatchAnalysis.analyze(noisy).map(_.copy(duplicatesDropped = 0))
    assert(a == b)
    assert(MatchAnalysis.analyze(noisy).map(_.duplicatesDropped).sum == noisy.size - clean.size)
  }

  // ---------------------------------------------------------------- casos borde, extremo a extremo
  private lazy val edge = MatchAnalysis.analyze(Simulator.generate(SimConfig(games = Nil, edgeCases = true)))

  test("E2E casos borde: dos rachas críticas en Battleship (3 y 4 impactos)") {
    val r      = report(edge, "edge-battleship-streak")
    val found  = r.patterns.filter(_.pattern == "DEADLY_STREAK")
    assert(found.map(_.playerId) == List("p1", "p1"))
    assert(found.map(_.details("streakLength")) == List(3.0, 4.0))
    val p1 = r.players.find(_.playerId == "p1").get
    assert(p1.metrics("shots") == 11 && p1.metrics("hits") == 9) // M H H H H H M H H H H
    assert(p1.metrics("accuracy") == 0.8182)                     // 9 / 11
    assert(r.players.find(_.playerId == "p2").get.metrics("accuracy") == 0)
  }

  test("E2E casos borde: remontada solo cuando corresponde") {
    assert(report(edge, "edge-football-comeback").patterns.map(p => (p.pattern, p.playerId)) == List(("COMEBACK", "p2")))
    assert(report(edge, "edge-football-no-comeback").patterns.isEmpty)
    val incomplete = report(edge, "edge-football-incomplete")
    assert(incomplete.patterns.isEmpty && incomplete.status == "IN_PROGRESS")
  }

  test("E2E casos borde: ventana de 10 s de Agar.io") {
    val p = report(edge, "edge-agario-window").players.find(_.playerId == "usr_1").get
    assert(p.crossEventMetrics("massGainedWithin10sOfSplit") == 150)
    assert(p.crossEventMetrics("splitsWithKill") == 1 && p.metrics("splits") == 2)
  }

  test("E2E casos borde: eficiencia de movimiento de Snake") {
    val p = report(edge, "edge-snake-route").players.find(_.playerId == "p1").get
    assert(p.crossEventMetrics("avgDirectionChangesBetweenFood") == 2) // intervalos de 3 y 1 giros
    assert(p.metrics("peakScore") == 30)
  }

  test("E2E casos borde: un juego nuevo (ajedrez) entra sin tocar el núcleo") {
    val r = report(edge, "edge-new-game-chess")
    assert(r.status == "FINISHED" && r.matchMetrics("durationSeconds") == 20)
    assert(r.warnings.exists(_.contains("sin analizador específico")))
  }

  // ----------------------------------------------------------------------------- JSON de salida
  test("el JSON de salida es válido, versionado y omite valores indefinidos") {
    val output = MatchAnalysis.analyzeInput(EventParser.parseInput(EnvelopeCodec.toNdjson(Simulator.generate(SimConfig(games = Nil, edgeCases = true)))))
    val text   = ReportJson.render(output)
    val json   = parse(text).toOption.get
    assert(json.hcursor.get[String]("schemaVersion") == Right("1.0"))
    assert(json.hcursor.downField("matches").values.exists(_.size == 7))
    assert(text.contains("\"shots\" : 11")) // enteros sin decimales
    assert(!text.contains("null"))          // los Option vacíos se omiten
    assert(!text.contains("NaN") && !text.contains("Infinity"))
  }

  test("los eventos inválidos se reportan en la salida sin detener el análisis") {
    val good   = EnvelopeCodec.toNdjson(Simulator.generate(SimConfig(games = Nil, edgeCases = true)))
    val output = MatchAnalysis.analyzeInput(EventParser.parseInput(good + "{\"eventId\":\"x\"}\n"))
    assert(output.invalidEvents.size == 1 && output.matches.size == 7)
  }
