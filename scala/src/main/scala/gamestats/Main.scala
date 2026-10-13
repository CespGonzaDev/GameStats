package gamestats

import gamestats.analytics.MatchAnalysis
import gamestats.report.ReportJson
import gamestats.parsing.{EnvelopeCodec, EventParser}
import gamestats.server.AnalyticsServer
import gamestats.sim.{SimConfig, Simulator}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.annotation.tailrec
import scala.util.Try

/** Punto de entrada. Es la única capa con efectos (leer/escribir archivos y stdin/stdout);
  * todo lo demás son funciones puras.
  *
  * {{{
  *   analyze <entrada|-> [--out <archivo>]
  *   simulate [--games ...] [--matches N] [--seed N] [--out <archivo>] [--format ndjson|json]
  *            [--shuffle] [--duplicates] [--edge-cases]
  *   serve [--host H] [--port N] [--threads N]
  * }}}
  */
object Main:

  val Usage: String =
    """GameStats - módulo analítico (Scala)
      |
      |Uso:
      |  analyze <entrada|-> [--out <archivo>]
      |      Lee eventos (NDJSON, arreglo JSON o {"events":[...]}) de un archivo o de stdin ("-")
      |      y escribe el JSON de resultados (stdout si no se indica --out).
      |
      |  simulate [--games snake,agario,battleship,head_football] [--matches N] [--seed N]
      |           [--start 2026-10-07T19:30:00Z] [--format ndjson|json] [--out <archivo>]
      |           [--shuffle] [--duplicates] [--edge-cases]
      |      Genera eventos sintéticos (stdout si no se indica --out).
      |
      |  serve [--host 127.0.0.1] [--port 8080] [--threads N]
      |      Levanta el servicio HTTP para el módulo Go (el puerto también puede venir de la
      |      variable de entorno PORT):
      |        GET  /health    -> estado del servicio
      |        POST /analyze   -> eventos en el cuerpo, devuelve el análisis en JSON
      |""".stripMargin

  def main(args: Array[String]): Unit =
    val code = args.toList match
      case "analyze" :: rest  => analyzeCommand(parseArgs(rest))
      case "simulate" :: rest => simulateCommand(parseArgs(rest))
      case "serve" :: rest    => serveCommand(parseArgs(rest))
      case "--help" :: _ | "-h" :: _ | Nil =>
        println(Usage)
        0
      case other :: _ =>
        System.err.println(s"Comando desconocido: $other\n\n$Usage")
        2
    if code != 0 then sys.exit(code)

  /** Separa argumentos posicionales de opciones `--clave valor` / `--bandera`. */
  def parseArgs(args: List[String]): (List[String], Map[String, String]) =
    @tailrec def loop(rest: List[String], positional: List[String], options: Map[String, String])
        : (List[String], Map[String, String]) =
      rest match
        case Nil => (positional.reverse, options)
        case key :: value :: tail if key.startsWith("--") && !value.startsWith("--") =>
          loop(tail, positional, options.updated(key.drop(2), value))
        case key :: tail if key.startsWith("--") =>
          loop(tail, positional, options.updated(key.drop(2), "true"))
        case other :: tail => loop(tail, other :: positional, options)
    loop(args, Nil, Map.empty)

  private def analyzeCommand(parsed: (List[String], Map[String, String])): Int =
    val (positional, options) = parsed
    positional.headOption match
      case None =>
        System.err.println("Falta la entrada (archivo o '-').\n\n" + Usage)
        2
      case Some(source) =>
        Try(readAll(source)).toEither match
          case Left(error) =>
            System.err.println(s"No se pudo leer '$source': ${error.getMessage}")
            1
          case Right(text) =>
            val input  = EventParser.parseInput(text)
            val output = MatchAnalysis.analyzeInput(input)
            write(options.get("out"), ReportJson.render(output) + "\n")
            System.err.println(
              s"${input.events.size} eventos válidos, ${input.invalid.size} inválidos, ${output.matches.size} partidas analizadas"
            )
            0

  private def simulateCommand(parsed: (List[String], Map[String, String])): Int =
    val (_, options) = parsed
    val defaults     = SimConfig()
    val games        = options.get("games").map(_.split(",").toList.map(_.trim).filter(_.nonEmpty)).getOrElse(defaults.games)
    val unknown      = games.filterNot(Simulator.SupportedGames.contains)

    val config = for
      matches <- numeric(options, "matches", defaults.matchesPerGame.toLong)
      seed    <- numeric(options, "seed", defaults.seed)
      start   <- options.get("start").fold(Right(defaults.start))(s => Try(Instant.parse(s)).toEither.left.map(_ => s"--start inválido: $s"))
    yield SimConfig(
      games = games,
      matchesPerGame = matches.toInt,
      seed = seed,
      start = start,
      shuffle = options.contains("shuffle"),
      duplicates = options.contains("duplicates"),
      edgeCases = options.contains("edge-cases")
    )

    if unknown.nonEmpty then
      System.err.println(s"Juegos no soportados por el simulador: ${unknown.mkString(", ")}. Disponibles: ${Simulator.SupportedGames.mkString(", ")}")
      2
    else
      config match
        case Left(message) =>
          System.err.println(message)
          2
        case Right(cfg) =>
          val events = Simulator.generate(cfg)
          val text =
            if options.get("format").contains("json") then EnvelopeCodec.toJsonArray(events) + "\n"
            else EnvelopeCodec.toNdjson(events)
          write(options.get("out"), text)
          System.err.println(s"${events.size} eventos generados (semilla ${cfg.seed})")
          0

  /** Levanta el servicio HTTP y bloquea hasta que el proceso termina (Ctrl+C / SIGTERM). */
  private def serveCommand(parsed: (List[String], Map[String, String])): Int =
    val (_, options) = parsed
    val host         = options.getOrElse("host", "127.0.0.1")
    val portDefault  = sys.env.get("PORT").flatMap(_.toLongOption).getOrElse(8080L)
    val cores        = Runtime.getRuntime.availableProcessors.toLong

    val config = for
      port    <- numeric(options, "port", portDefault)
      threads <- numeric(options, "threads", Math.max(4L, cores))
      _       <- Either.cond(port >= 0 && port <= 65535, (), s"--port fuera de rango: $port")
      _       <- Either.cond(threads >= 1, (), s"--threads debe ser >= 1: $threads")
    yield (port.toInt, threads.toInt)

    config match
      case Left(message) =>
        System.err.println(message)
        2
      case Right((port, threads)) =>
        Try(AnalyticsServer.start(host, port, threads)).toEither match
          case Left(error) =>
            System.err.println(s"No se pudo iniciar el servidor en $host:$port: ${error.getMessage}")
            1
          case Right(running) =>
            Runtime.getRuntime.addShutdownHook(new Thread(() => running.stop()))
            System.err.println(s"GameStats Scala escuchando en http://$host:${running.port} (POST /analyze, GET /health, $threads hilos)")
            Thread.currentThread.join() // el servidor corre en sus propios hilos
            0

  private def numeric(options: Map[String, String], key: String, default: Long): Either[String, Long] =
    options.get(key).fold(Right(default))(raw => raw.toLongOption.toRight(s"--$key debe ser un número: $raw"))

  private def readAll(source: String): String =
    if source == "-" then new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
    else Files.readString(Paths.get(source), StandardCharsets.UTF_8)

  private def write(target: Option[String], text: String): Unit =
    target match
      case Some(file) =>
        val path = Paths.get(file)
        Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
        Files.writeString(path, text, StandardCharsets.UTF_8)
        ()
      case None =>
        print(text)
