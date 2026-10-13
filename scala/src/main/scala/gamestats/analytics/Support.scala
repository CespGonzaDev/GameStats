package gamestats.analytics

import gamestats.model.Envelope
import io.circe.Decoder

import java.time.{Duration, Instant}

/** Preparación de la línea de tiempo de una partida. */
object Timeline:

  /** Descarta duplicados por `eventId` (reentregas "at-least-once") y ordena por timestamp.
    * El orden es estable: ante timestamps iguales se respeta el orden de llegada.
    *
    * @return (eventos normalizados, cantidad de duplicados descartados)
    */
  def normalize(events: List[Envelope]): (Vector[Envelope], Int) =
    val unique = events.distinctBy(_.eventId)
    val sorted = unique.sortBy(e => (e.timestamp.getEpochSecond, e.timestamp.getNano))
    (sorted.toVector, events.size - unique.size)

/** Funciones numéricas puras. Ninguna devuelve NaN/Infinity: lo indefinido es `None`. */
object Metrics:

  def round4(d: Double): Double =
    BigDecimal(d).setScale(4, BigDecimal.RoundingMode.HALF_UP).toDouble

  def ratio(numerator: Double, denominator: Double): Option[Double] =
    Option.when(denominator != 0)(numerator / denominator)

  def mean(xs: Seq[Double]): Option[Double] =
    Option.when(xs.nonEmpty)(xs.sum / xs.size)

  def seconds(from: Instant, to: Instant): Double =
    Duration.between(from, to).toMillis / 1000.0

  /** Construye un mapa de métricas descartando las indefinidas y redondeando a 4 decimales. */
  def collect(entries: (String, Option[Double])*): Map[String, Double] =
    entries.collect { case (name, Some(v)) if v.isFinite => name -> round4(v) }.toMap

  def defined(name: String, value: Double): (String, Option[Double]) = name -> Some(value)

  /** Histograma: cuántos elementos caen en cada clave. */
  def counts[A](xs: Iterable[A])(key: A => String): Map[String, Int] =
    xs.groupMapReduce(key)(_ => 1)(_ + _)

/** Decodificación tipada del `data` de los eventos a case classes de cada juego. */
object Payloads:

  /** Decodifica todos los eventos de una acción. Los payloads inválidos no rompen el análisis:
    * se devuelven como advertencias y el evento se omite de las métricas.
    */
  def decode[A: Decoder](events: Vector[Envelope], action: String): (Vector[(Envelope, A)], List[String]) =
    val attempts = events.filter(_.action == action).map { e =>
      e.data
        .as[A]
        .left
        .map(err => s"$action (eventId=${e.eventId}): payload inválido, se omite del análisis: ${err.getMessage}")
        .map(payload => (e, payload))
    }
    (attempts.collect { case Right(ok) => ok }, attempts.collect { case Left(w) => w }.toList)
