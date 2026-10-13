package gamestats.server

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import gamestats.analytics.GameRegistry

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ExecutorService, Executors}
import scala.util.control.NonFatal

/** Servidor HTTP en ejecución. */
final case class RunningServer(server: HttpServer, pool: ExecutorService):
  def port: Int = server.getAddress.getPort

  def stop(): Unit =
    server.stop(1)
    pool.shutdown()

/** Capa de efectos del modo servicio: abre el socket y delega todo en [[Routes.route]].
  *
  * Usa el servidor HTTP incluido en el JDK (`com.sun.net.httpserver`), sin dependencias nuevas.
  * Las peticiones se atienden en un pool de hilos; como el análisis es una función pura sin
  * estado compartido, varias partidas pueden analizarse en paralelo sin sincronización.
  */
object AnalyticsServer:

  def start(
      host: String,
      port: Int,
      threads: Int,
      registry: GameRegistry = GameRegistry.default
  ): RunningServer =
    val server = HttpServer.create(new InetSocketAddress(host, port), 0)
    val pool   = Executors.newFixedThreadPool(threads)
    server.setExecutor(pool)
    server.createContext("/", exchange => handle(registry, exchange))
    server.start()
    RunningServer(server, pool)

  private def handle(registry: GameRegistry, exchange: HttpExchange): Unit =
    val result =
      try
        // Se lee un byte más que el máximo para detectar cuerpos demasiado grandes sin cargarlos enteros.
        val body = exchange.getRequestBody.readNBytes(Routes.MaxBodyBytes + 1)
        Routes.route(registry)(exchange.getRequestMethod, exchange.getRequestURI.getPath, body)
      catch case NonFatal(e) => HttpResult(500, s"""{"error":"${e.getClass.getSimpleName}"}""")

    val bytes = result.body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(result.status, bytes.length.toLong)
    val out = exchange.getResponseBody
    try out.write(bytes)
    finally
      out.close()
      exchange.close()
