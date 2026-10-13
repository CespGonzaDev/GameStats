package gamestats

import org.scalatest.funsuite.AnyFunSuite

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Verifica que el módulo analítico respete el paradigma funcional exigido: sin `var`, sin
  * colecciones mutables, sin bucles imperativos (`while`/`for` sin `yield` se evalúan aparte) ni `return`.
  *
  * Aplica a los paquetes `model`, `parsing`, `analytics` y `sim` (las E/S están aisladas en `Main`).
  */
class FunctionalPurityCheck extends AnyFunSuite:

  private val packages = List("model", "parsing", "analytics", "sim")
  private val forbidden: List[(String, scala.util.matching.Regex)] = List(
    "var"                   -> """\bvar\b""".r,
    "colecciones mutables"  -> """scala\.collection\.mutable|\bmutable\.|import\s+scala\.collection\.mutable""".r,
    "while"                 -> """\bwhile\b""".r,
    "return"                -> """\breturn\b""".r,
    "ArrayBuffer/ListBuffer" -> """\b(ArrayBuffer|ListBuffer|StringBuilder)\b""".r
  )

  private def scalaFiles(pkg: String): List[Path] =
    val dir = Paths.get("src", "main", "scala", "gamestats", pkg)
    Files.walk(dir).iterator().asScala.filter(_.toString.endsWith(".scala")).toList

  /** Quita comentarios para no marcar la documentación. */
  private def code(path: Path): String =
    Files
      .readString(path, StandardCharsets.UTF_8)
      .replaceAll("(?s)/\\*.*?\\*/", "")
      .linesIterator
      .map(_.replaceAll("//.*$", ""))
      .mkString("\n")

  for pkg <- packages do
    test(s"paquete '$pkg': sin construcciones imperativas ni estado mutable") {
      val files = scalaFiles(pkg)
      assert(files.nonEmpty)
      val violations = for
        file            <- files
        (label, regex)  <- forbidden
        found            = regex.findFirstIn(code(file))
        if found.isDefined
      yield s"${file.getFileName}: $label"
      assert(violations.isEmpty, violations.mkString("; "))
    }
