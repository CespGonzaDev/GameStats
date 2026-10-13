package gamestats.sim

import scala.annotation.tailrec

/** Generador pseudoaleatorio inmutable (SplitMix64). Cada llamada devuelve el valor y el *nuevo*
  * generador, de modo que el simulador es una función pura de la semilla: misma semilla, mismos datos.
  */
final case class Rng(state: Long):

  def nextLong: (Long, Rng) =
    val s  = state + 0x9e3779b97f4a7c15L
    val z1 = (s ^ (s >>> 30)) * 0xbf58476d1ce4e5b9L
    val z2 = (z1 ^ (z1 >>> 27)) * 0x94d049bb133111ebL
    (z2 ^ (z2 >>> 31), Rng(s))

  /** Entero uniforme en [0, bound). */
  def nextInt(bound: Int): (Int, Rng) =
    val (l, next) = nextLong
    (Math.floorMod(l, bound.toLong).toInt, next)

  def nextDouble: (Double, Rng) =
    val (l, next) = nextLong
    ((l >>> 11).toDouble / (1L << 53).toDouble, next)

/** Mónada de estado sobre [[Rng]]: describe un cálculo aleatorio sin ejecutarlo. */
final case class Gen[+A](run: Rng => (A, Rng)):

  def map[B](f: A => B): Gen[B] =
    Gen { rng =>
      val (a, next) = run(rng)
      (f(a), next)
    }

  def flatMap[B](f: A => Gen[B]): Gen[B] =
    Gen { rng =>
      val (a, next) = run(rng)
      f(a).run(next)
    }

object Gen:

  def pure[A](a: A): Gen[A] = Gen(rng => (a, rng))

  val long: Gen[Long] = Gen(_.nextLong)

  val double: Gen[Double] = Gen(_.nextDouble)

  /** Entero uniforme en [0, bound). */
  def int(bound: Int): Gen[Int] = Gen(_.nextInt(bound))

  /** Entero uniforme en [lo, hi] (ambos inclusive). */
  def between(lo: Int, hi: Int): Gen[Int] = int(hi - lo + 1).map(_ + lo)

  def chance(p: Double): Gen[Boolean] = double.map(_ < p)

  def pick[A](xs: Seq[A]): Gen[A] = int(xs.size).map(xs)

  /** Elección ponderada: `(valor, peso)`. */
  def weighted[A](options: List[(A, Int)]): Gen[A] =
    int(options.map(_._2).sum).map { roll =>
      options
        .scanLeft((Option.empty[A], 0)) { case ((_, acc), (a, w)) => (Some(a), acc + w) }
        .drop(1)
        .collectFirst { case (Some(a), upTo) if roll < upTo => a }
        .getOrElse(options.last._1)
    }

  /** UUID v4 determinista (a partir del generador). */
  val uuid: Gen[String] =
    for
      hi <- long
      lo <- long
    yield
      val msb = (hi & ~0xf000L) | 0x4000L
      val lsb = (lo & 0x3fffffffffffffffL) | Long.MinValue
      new java.util.UUID(msb, lsb).toString

  def sequence[A](gens: List[Gen[A]]): Gen[List[A]] =
    Gen { rng0 =>
      val (acc, rng) = gens.foldLeft((Vector.empty[A], rng0)) { case ((acc, rng), gen) =>
        val (a, next) = gen.run(rng)
        (acc :+ a, next)
      }
      (acc.toList, rng)
    }

  /** Genera elementos mientras `step` devuelva `Some((elementos, nuevoEstado))`. Devuelve todo lo
    * emitido y el estado final. Es recursivo de cola: seguro para simulaciones largas.
    */
  def unfold[S, A](initial: S)(step: S => Gen[Option[(List[A], S)]]): Gen[(List[A], S)] =
    Gen { rng0 =>
      @tailrec def loop(state: S, rng: Rng, acc: Vector[A]): ((List[A], S), Rng) =
        step(state).run(rng) match
          case (None, next)                    => ((acc.toList, state), next)
          case (Some((emitted, newState)), next) => loop(newState, next, acc ++ emitted)
      loop(initial, rng0, Vector.empty)
    }

  /** Mezcla una lista asignando una clave aleatoria a cada elemento. */
  def shuffle[A](xs: List[A]): Gen[List[A]] =
    sequence(xs.map(a => long.map(key => (key, a)))).map(_.sortBy(_._1).map(_._2))
