package gamestats.server

import gamestats.analytics.{GameRegistry, MatchAnalysis}
import gamestats.model.AnalysisOutput
import gamestats.parsing.EventParser
import gamestats.report.ReportJson
import io.circe.Json
import io.circe.syntax.*

import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/** Respuesta HTTP como valor: el enrutamiento es una función pura y se prueba sin abrir sockets. */
final case class HttpResult(status: Int, body: String)

/** Contrato HTTP del módulo analítico (ver README del módulo Go).
  *
  *  - `GET  /health`  -> 200 `{"status":"ok", ...}`
  *  - `POST /analyze` -> cuerpo: eventos (NDJSON, arreglo JSON o `{"events":[...]}`).
  *      200 análisis (`docs/results.schema.json`) · 400 cuerpo vacío · 413 cuerpo demasiado grande ·
  *      422 ningún evento válido (el cuerpo trae el detalle en `invalidEvents`)
  *  - cualquier otra ruta -> 404; ruta conocida con otro método -> 405; fallo interno -> 500.
  *
  * El servicio no guarda estado: la misma entrada produce siempre la misma salida, por lo que
  * reintentar un POST es seguro.
  */
object Routes:

  /** Tamaño máximo aceptado para el cuerpo de un POST (64 MiB). */
  val MaxBodyBytes: Int = 64 * 1024 * 1024

  private def error(status: Int, message: String): HttpResult =
    HttpResult(status, Json.obj("error" -> message.asJson).noSpaces)

  def route(registry: GameRegistry)(method: String, path: String, body: Array[Byte]): HttpResult =
    (method.toUpperCase, path) match
      case ("GET", "/health") =>
        HttpResult(
          200,
          Json.obj("status" -> "ok".asJson, "service" -> "gamestats-analytics".asJson, "schemaVersion" -> AnalysisOutput.SchemaVersion.asJson).noSpaces
        )
      case ("POST", "/analyze") => analyze(registry, body)
      case (_, "/health") | (_, "/analyze") => error(405, s"método $method no permitido en $path")
      case _                                => error(404, s"ruta no encontrada: $path")

  private def analyze(registry: GameRegistry, body: Array[Byte]): HttpResult =
    if body.length > MaxBodyBytes then error(413, s"el cuerpo supera el máximo de $MaxBodyBytes bytes")
    else
      val text = new String(body, StandardCharsets.UTF_8)
      if text.isBlank then error(400, "cuerpo vacío: se esperaban eventos (NDJSON, arreglo JSON o {\"events\":[...]})")
      else
        try
          val input  = EventParser.parseInput(text)
          val output = MatchAnalysis.analyzeInput(input, registry)
          val status = if input.events.isEmpty && input.invalid.nonEmpty then 422 else 200
          HttpResult(status, ReportJson.render(output))
        catch case NonFatal(e) => error(500, s"error interno al analizar: ${e.getMessage}")
