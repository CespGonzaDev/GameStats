package gamestats.analytics.games

import gamestats.analytics.*
import gamestats.model.{Envelope, PlayerReport}
import io.circe.Decoder

import java.time.Duration

/** Combate IO tipo Agar.io / Slither.io (física 2D continua, masa, multijugador).
  *
  * Eventos analizados:
  *  - `SPLIT`: `{massBefore, cellCount, vector}`
  *  - `EAT_PLAYER`: `{victimId, massGained, totalMass}`
  *
  * Métrica que relaciona tipos de evento (inciso C.3): factor de conversión de riesgo = masa
  * consumida (EAT_PLAYER) dentro de los 10 segundos posteriores a un SPLIT, comparada contra la
  * masa que se arriesgó al dividirse. Cada EAT_PLAYER se atribuye a lo sumo a un SPLIT (el más
  * reciente del mismo jugador dentro de la ventana), así no se cuenta masa dos veces.
  *
  * Métrica por partida (inciso C.2): `peakMass`, la masa máxima alcanzada en la sala.
  */
object Agario extends GameAnalyzer:

  val gameId: String = "agario"

  val Split: String     = "SPLIT"
  val EatPlayer: String = "EAT_PLAYER"

  /** Ventana posterior a un SPLIT en la que un EAT_PLAYER se considera consecuencia del riesgo. */
  val RiskWindow: Duration = Duration.ofSeconds(10)

  final case class SplitPayload(massBefore: Double, cellCount: Option[Int])
  final case class EatPayload(victimId: String, massGained: Double, totalMass: Option[Double])

  given Decoder[SplitPayload] = Decoder.forProduct2("massBefore", "cellCount")(SplitPayload.apply)
  given Decoder[EatPayload] =
    Decoder.forProduct3("victimId", "massGained", "totalMass")(EatPayload.apply)

  private type Timed[A] = (Envelope, A)

  def analyze(ctx: MatchContext): GameAnalysis =
    val (splits, splitWarnings) = Payloads.decode[SplitPayload](ctx.events, Split)
    val (eats, eatWarnings)     = Payloads.decode[EatPayload](ctx.events, EatPlayer)

    val rows = ctx.players.map { playerId =>
      val mySplits = splits.filter(_._1.playerId == playerId)
      val myEats   = eats.filter(_._1.playerId == playerId)

      val consumed = myEats.map(_._2.massGained).sum
      val peak = (mySplits.map(_._2.massBefore) ++ myEats.flatMap(_._2.totalMass)).maxOption

      // Atribución de cada EAT_PLAYER al SPLIT más reciente dentro de la ventana.
      val attributed: Vector[(Timed[SplitPayload], Timed[EatPayload])] =
        myEats.flatMap(eat => mySplits.filter(split => inWindow(split._1, eat._1)).lastOption.map(_ -> eat))
      val gainedAfterSplit = attributed.map(_._2._2.massGained).sum
      val splitsWithKill   = attributed.map(_._1._1.eventId).distinct.size
      val massRisked       = mySplits.map(_._2.massBefore).sum

      val report = PlayerReport(
        playerId = playerId,
        metrics = Metrics.collect(
          Metrics.defined("splits", mySplits.size.toDouble),
          Metrics.defined("kills", myEats.size.toDouble),
          Metrics.defined("massConsumed", consumed),
          "peakMass" -> peak
        ),
        distributions = Map.empty,
        crossEventMetrics = Metrics.collect(
          Metrics.defined("massGainedWithin10sOfSplit", gainedAfterSplit),
          Metrics.defined("splitsWithKill", splitsWithKill.toDouble),
          "splitSuccessRate"     -> Metrics.ratio(splitsWithKill.toDouble, mySplits.size.toDouble),
          "riskConversionFactor" -> Metrics.ratio(gainedAfterSplit, massRisked)
        )
      )
      (report, peak)
    }

    GameAnalysis(
      players = rows.map(_._1),
      matchMetrics = Metrics.collect(
        "peakMass" -> rows.flatMap(_._2).maxOption,
        Metrics.defined("totalKills", eats.size.toDouble)
      ),
      warnings = splitWarnings ++ eatWarnings
    )

  /** `eat` ocurre en el instante del `split` o hasta [[RiskWindow]] después (límite inclusivo). */
  private def inWindow(split: Envelope, eat: Envelope): Boolean =
    val elapsed = Duration.between(split.timestamp, eat.timestamp)
    !elapsed.isNegative && elapsed.compareTo(RiskWindow) <= 0
