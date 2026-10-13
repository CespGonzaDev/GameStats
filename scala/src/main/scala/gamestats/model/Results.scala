package gamestats.model

import java.time.Instant

/** Una secuencia compleja detectada (p. ej. COMEBACK o DEADLY_STREAK). */
final case class PatternDetection(
    pattern: String,
    playerId: String,
    startedAt: Instant,
    endedAt: Instant,
    eventIds: List[String],
    description: String,
    details: Map[String, Double]
)

/** Resultado analítico de un jugador dentro de una partida.
  *
  *  - `metrics`: agregaciones por jugador (ratios, promedios, conteos).
  *  - `distributions`: histogramas (p. ej. tipos de disparo).
  *  - `crossEventMetrics`: métricas que relacionan distintos tipos de eventos.
  */
final case class PlayerReport(
    playerId: String,
    metrics: Map[String, Double] = Map.empty,
    distributions: Map[String, Map[String, Int]] = Map.empty,
    crossEventMetrics: Map[String, Double] = Map.empty
):
  /** Combina dos reportes del mismo jugador (los de `other` ganan ante claves repetidas). */
  def merge(other: PlayerReport): PlayerReport =
    PlayerReport(
      playerId,
      metrics ++ other.metrics,
      distributions ++ other.distributions,
      crossEventMetrics ++ other.crossEventMetrics
    )

/** Reporte completo de una partida. */
final case class MatchReport(
    matchId: String,
    gameId: String,
    gameVersion: String,
    status: String,
    startedAt: Option[Instant],
    finishedAt: Option[Instant],
    eventCount: Int,
    duplicatesDropped: Int,
    actionCounts: Map[String, Int],
    matchMetrics: Map[String, Double],
    players: List[PlayerReport],
    patterns: List[PatternDetection],
    warnings: List[String]
)

/** Evento que no pasó la validación estructural al parsear. */
final case class InvalidEvent(source: String, reason: String)

/** Documento raíz que expone el módulo Scala. */
final case class AnalysisOutput(
    schemaVersion: String,
    matches: List[MatchReport],
    invalidEvents: List[InvalidEvent]
)

object AnalysisOutput:
  val SchemaVersion: String = "1.0"
