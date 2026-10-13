package gamestats.server

import gamestats.analytics.GameRegistry
import gamestats.parsing.EnvelopeCodec
import gamestats.sim.{SimConfig, Simulator}
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{CompletableFuture, Executors}

class ServerSpec extends AnyFunSuite with BeforeAndAfterAll:

  private val route = Routes.route(GameRegistry.default)

  private lazy val edgeEvents = Simulator.generate(SimConfig(games = Nil, edgeCases = true))
  private lazy val edgeBody   = EnvelopeCodec.toNdjson(edgeEvents)

  private def json(text: String): Json = parse(text).toOption.get
  private def post(body: String)       = route("POST", "/analyze", body.getBytes(UTF_8))

  // ------------------------------------------------------------- rutas puras (sin sockets)
  test("GET /health responde ok") {
    val r = route("GET", "/health", Array.emptyByteArray)
    assert(r.status == 200)
    assert(json(r.body).hcursor.get[String]("status") == Right("ok"))
  }

  test("POST /analyze devuelve el análisis de las partidas recibidas") {
    val r = post(edgeBody)
    assert(r.status == 200)
    val out = json(r.body)
    assert(out.hcursor.get[String]("schemaVersion") == Right("1.0"))
    assert(out.hcursor.downField("matches").values.exists(_.size == 7))
    assert(r.body.contains("DEADLY_STREAK") && r.body.contains("COMEBACK"))
  }

  test("POST /analyze acepta los tres formatos: NDJSON, arreglo y lote {events}") {
    val events = edgeEvents.filter(_.matchId == "edge-agario-window")
    val asArray = post(EnvelopeCodec.toJsonArray(events))
    val asBatch = post(s"""{"events": ${EnvelopeCodec.toJsonArray(events)}}""")
    val asLines = post(EnvelopeCodec.toNdjson(events))
    assert(List(asArray, asBatch, asLines).forall(_.status == 200))
    assert(asArray.body == asBatch.body && asBatch.body == asLines.body)
  }

  test("es idempotente: reenviar el mismo cuerpo da exactamente la misma respuesta") {
    assert(post(edgeBody) == post(edgeBody))
  }

  test("cuerpo vacío -> 400") {
    assert(post("").status == 400)
    assert(post("  \n ").status == 400)
  }

  test("sin ningún evento válido -> 422 con el detalle en invalidEvents") {
    val r = post("""{"eventId":"x"}""")
    assert(r.status == 422)
    assert(json(r.body).hcursor.downField("invalidEvents").values.exists(_.size == 1))
  }

  test("eventos válidos mezclados con inválidos -> 200 y los inválidos se reportan") {
    val r = post(edgeBody + """{"eventId":"x"}""" + "\n")
    assert(r.status == 200)
    assert(json(r.body).hcursor.downField("invalidEvents").values.exists(_.size == 1))
  }

  test("un arreglo vacío es válido y no produce partidas") {
    val r = post("[]")
    assert(r.status == 200)
    assert(json(r.body).hcursor.downField("matches").values.exists(_.isEmpty))
  }

  test("método incorrecto -> 405 y ruta desconocida -> 404") {
    assert(route("GET", "/analyze", Array.emptyByteArray).status == 405)
    assert(route("POST", "/health", Array.emptyByteArray).status == 405)
    assert(route("GET", "/nada", Array.emptyByteArray).status == 404)
  }

  // ------------------------------------------------------------- servidor real (sockets)
  private lazy val running = AnalyticsServer.start("127.0.0.1", 0, 4)
  private lazy val client  = HttpClient.newHttpClient()
  private def url(path: String): URI = URI.create(s"http://127.0.0.1:${running.port}$path")

  override protected def afterAll(): Unit = running.stop()

  private def send(request: HttpRequest): HttpResponse[String] =
    client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))

  private def postHttp(body: String): HttpResponse[String] =
    send(HttpRequest.newBuilder(url("/analyze")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, UTF_8)).build())

  test("servidor real: /health y /analyze por HTTP") {
    val health = send(HttpRequest.newBuilder(url("/health")).GET().build())
    assert(health.statusCode == 200)
    assert(health.headers.firstValue("Content-Type").orElse("").startsWith("application/json"))

    val analyzed = postHttp(edgeBody)
    assert(analyzed.statusCode == 200)
    assert(analyzed.body == post(edgeBody).body) // mismo resultado que la ruta pura
  }

  test("servidor real: respeta tildes y UTF-8 en el cuerpo y la respuesta") {
    val r = postHttp(edgeBody)
    assert(r.body.contains("remontó")) // texto con tilde generado por el analizador
  }

  test("servidor real: peticiones concurrentes (varias goroutines de Go) dan el mismo resultado") {
    val pool     = Executors.newFixedThreadPool(8)
    val expected = post(edgeBody).body
    try
      val futures = List.fill(24)(CompletableFuture.supplyAsync(() => postHttp(edgeBody), pool))
      val results = futures.map(_.get())
      assert(results.forall(_.statusCode == 200))
      assert(results.forall(_.body == expected))
    finally pool.shutdown()
  }
