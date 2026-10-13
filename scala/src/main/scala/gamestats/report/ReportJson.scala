package gamestats.report

import gamestats.model.*
import io.circe.{Encoder, Json, Printer}
import io.circe.syntax.*

/** Serialización del contrato de salida (ver docs/results.schema.json).
  *
  * Los `Option` ausentes se omiten del JSON; los números enteros se imprimen sin decimales.
  */
object ReportJson:

  private val printer: Printer = Printer.spaces2.copy(dropNullValues = true)

  private given Encoder[Double] = Encoder.instance { d =>
    if d.isWhole && d.abs < 1e15 then Json.fromLong(d.toLong) else Json.fromDoubleOrNull(d)
  }

  given Encoder[PatternDetection] = Encoder.AsObject.derived[PatternDetection]
  given Encoder[PlayerReport]     = Encoder.AsObject.derived[PlayerReport]
  given Encoder[MatchReport]      = Encoder.AsObject.derived[MatchReport]
  given Encoder[InvalidEvent]     = Encoder.AsObject.derived[InvalidEvent]
  given Encoder[AnalysisOutput]   = Encoder.AsObject.derived[AnalysisOutput]

  def toJson(output: AnalysisOutput): Json = output.asJson

  def render(output: AnalysisOutput): String = printer.print(toJson(output))
