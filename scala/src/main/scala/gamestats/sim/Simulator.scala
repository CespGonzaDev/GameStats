package gamestats.sim

import gamestats.model.Envelope

import java.time.Instant

/** Configuración del generador de eventos sintéticos. */
final case class SimConfig(
    games: List[String] = Simulator.SupportedGames,
    matchesPerGame: Int = 3,
    seed: Long = 42L,
    start: Instant = Instant.parse("2026-10-07T19:30:00Z"),
    shuffle: Boolean = false,
    duplicates: Boolean = false,
    edgeCases: Boolean = false
)

/** Generador de eventos sintéticos realistas: varias partidas en paralelo (sus eventos se
  * intercalan en el tiempo), varios juegos, y opcionalmente desorden y duplicados para simular
  * una entrega "at-least-once" y sin garantía de orden desde el módulo de ingestión.
  */
object Simulator:

  val SupportedGames: List[String] = List("snake", "agario", "battleship", "head_football")

  private def generator(game: String): Option[MatchSpec => Gen[List[Envelope]]] = game match
    case "snake"         => Some(SnakeSim.generate)
    case "agario"        => Some(AgarioSim.generate)
    case "battleship"    => Some(BattleshipSim.generate)
    case "head_football" => Some(HeadFootballSim.generate)
    case _               => None

  /** Un `Gen` con todas las partidas pedidas; las partidas arrancan escalonadas en el tiempo. */
  def program(config: SimConfig): Gen[List[Envelope]] =
    val slots: List[(String, Int)] =
      config.games.flatMap(game => List.tabulate(config.matchesPerGame)(i => (game, i + 1)))

    for
      matches <- Gen.sequence(slots.zipWithIndex.flatMap { case ((game, number), slot) =>
        generator(game).map { make =>
          Gen.between(0, 45000).flatMap { offset =>
            val spec = MatchSpec(game, s"$game-${"%03d".format(number)}", config.start.plusMillis(slot * 7000L + offset))
            make(spec)
          }
        }
      })
      edge    <- if config.edgeCases then EdgeCases.all else Gen.pure(List.empty[Envelope])
      ordered  = (matches.flatten ++ edge).sortBy(e => (e.timestamp.getEpochSecond, e.timestamp.getNano))
      repeated <- if config.duplicates then redeliveries(ordered) else Gen.pure(ordered)
      result   <- if config.shuffle then Gen.shuffle(repeated) else Gen.pure(repeated)
    yield result

  /** Reentrega ~5 % de los eventos (mismo `eventId`), como haría un productor "at-least-once". */
  private def redeliveries(events: List[Envelope]): Gen[List[Envelope]] =
    Gen.sequence(events.map(e => Gen.chance(0.05).map(again => if again then List(e, e) else List(e)))).map(_.flatten)

  /** Genera los eventos de forma determinista a partir de `config.seed`. */
  def generate(config: SimConfig): List[Envelope] =
    program(config).run(Rng(config.seed))._1
