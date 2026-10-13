package gamestats.analytics

import gamestats.model.*
import gamestats.parsing.ParsedInput

import java.time.Instant

/** Orquestador del módulo analítico: función pura de eventos a reportes.
  *
  * Pipeline por partida: deduplicar -> ordenar -> métricas globales (inciso C.2: duración e
  * intensidad) -> analizador específico del juego (si existe) -> mezclar resultados.
  */
object MatchAnalysis:

  val StatusFinished   = "FINISHED"
  val StatusInProgress = "IN_PROGRESS"
  val StatusUnknown    = "UNKNOWN"

  /** Analiza el contenido ya parseado de una entrada. */
  def analyzeInput(input: ParsedInput, registry: GameRegistry = GameRegistry.default): AnalysisOutput =
    AnalysisOutput(
      schemaVersion = AnalysisOutput.SchemaVersion,
      matches = analyze(input.events, registry),
      invalidEvents = input.invalid
    )

  /** Agrupa los eventos por `matchId` y analiza cada partida. Salida determinista (ordenada por
    * inicio de partida y luego por `matchId`).
    */
  def analyze(events: List[Envelope], registry: GameRegistry = GameRegistry.default): List[MatchReport] =
    events
      .groupBy(_.matchId)
      .toList
      .map((matchId, matchEvents) => analyzeMatch(matchId, matchEvents, registry))
      .sortBy(r => (r.startedAt.map(_.toEpochMilli).getOrElse(Long.MaxValue), r.matchId))

  def analyzeMatch(matchId: String, rawEvents: List[Envelope], registry: GameRegistry): MatchReport =
    val (events, duplicates) = Timeline.normalize(rawEvents)
    val first                = events.head // groupBy garantiza al menos un evento
    val ctx                  = MatchContext(matchId, first.gameId, first.gameVersion, events)

    val game: GameAnalysis = registry.find(ctx.gameId).map(_.analyze(ctx)).getOrElse(GameAnalysis())

    val duration: Option[Double] =
      for
        start <- ctx.startedAt
        end   <- ctx.finishedAt
        if !end.isBefore(start)
      yield Metrics.seconds(start, end)

    // Para la intensidad: duración efectiva; si la partida no cerró, el lapso entre el primer y último evento.
    val span: Option[Double] = duration.orElse(Some(Metrics.seconds(first.timestamp, events.last.timestamp)))

    val commonMetrics = Metrics.collect(
      "durationSeconds" -> duration,
      "eventsPerMinute" -> span.flatMap(s => Metrics.ratio(events.size.toDouble, s / 60.0))
    )

    val commonPlayers = ctx.players.map { playerId =>
      PlayerReport(
        playerId,
        metrics = Metrics.collect(Metrics.defined("eventCount", events.count(_.playerId == playerId).toDouble))
      )
    }
    val players = (commonPlayers ++ game.players)
      .groupBy(_.playerId)
      .values
      .map(_.reduce(_ merge _))
      .toList
      .sortBy(_.playerId)

    MatchReport(
      matchId = matchId,
      gameId = ctx.gameId,
      gameVersion = ctx.gameVersion,
      status = status(ctx),
      startedAt = ctx.startedAt,
      finishedAt = ctx.finishedAt,
      eventCount = events.size,
      duplicatesDropped = duplicates,
      actionCounts = Metrics.counts(events)(_.action),
      matchMetrics = commonMetrics ++ game.matchMetrics,
      players = players,
      patterns = game.patterns.sortBy(p => (p.startedAt.toEpochMilli, p.pattern, p.playerId)),
      warnings = consistencyWarnings(ctx, registry) ++ game.warnings
    )

  private def status(ctx: MatchContext): String =
    (ctx.startedAt, ctx.finishedAt) match
      case (Some(s), Some(f)) if !f.isBefore(s) => StatusFinished
      case (Some(_), None)                      => StatusInProgress
      case _                                    => StatusUnknown

  private def consistencyWarnings(ctx: MatchContext, registry: GameRegistry): List[String] =
    val mixedGames = ctx.events.map(_.gameId).distinct
    List(
      Option.when(mixedGames.size > 1)(
        s"la partida mezcla gameId distintos (${mixedGames.mkString(", ")}); se usó '${ctx.gameId}'"
      ),
      Option.when(ctx.startedAt.isEmpty)("no se recibió MATCH_STARTED"),
      Option.when(ctx.finishedAt.isEmpty)("no se recibió MATCH_FINISHED (partida incompleta)"),
      for
        s <- ctx.startedAt
        f <- ctx.finishedAt
        if f.isBefore(s)
      yield "MATCH_FINISHED ocurre antes que MATCH_STARTED",
      Option.when(registry.find(ctx.gameId).isEmpty)(
        s"gameId '${ctx.gameId}' sin analizador específico: solo se calcularon métricas globales"
      )
    ).flatten
