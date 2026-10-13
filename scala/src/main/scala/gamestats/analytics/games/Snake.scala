package gamestats.analytics.games

import gamestats.analytics.*
import gamestats.model.PlayerReport
import io.circe.Decoder

/** Snake (arcade / supervivencia en grilla 2D).
  *
  * Eventos analizados:
  *  - `EAT_FOOD`: `{foodType, points, currentLength, coords}`
  *  - `COLLISION`: `{collisionWith, finalScore}`
  *  - `CHANGE_DIRECTION`: solo se cuenta (no se decodifica su payload).
  *
  * Métrica que relaciona tipos de evento (inciso C.3): eficiencia de movimiento = cantidad de
  * `CHANGE_DIRECTION` entre eventos sucesivos de `EAT_FOOD` (menos giros = ruta más directa).
  *
  * Métrica por partida (inciso C.2): `peakScore`, la puntuación máxima alcanzada en la sala
  * (el mayor entre los puntos acumulados por comida y el `finalScore` de la colisión).
  */
object Snake extends GameAnalyzer:

  val gameId: String = "snake"

  val EatFood: String         = "EAT_FOOD"
  val Collision: String       = "COLLISION"
  val ChangeDirection: String = "CHANGE_DIRECTION"

  final case class FoodPayload(foodType: String, points: Int, currentLength: Option[Int])
  final case class CollisionPayload(collisionWith: Option[String], finalScore: Option[Int])

  given Decoder[FoodPayload] =
    Decoder.forProduct3("foodType", "points", "currentLength")(FoodPayload.apply)
  given Decoder[CollisionPayload] =
    Decoder.forProduct2("collisionWith", "finalScore")(CollisionPayload.apply)

  def analyze(ctx: MatchContext): GameAnalysis =
    val (foods, foodWarnings)           = Payloads.decode[FoodPayload](ctx.events, EatFood)
    val (collisions, collisionWarnings) = Payloads.decode[CollisionPayload](ctx.events, Collision)

    val rows = ctx.players.map { playerId =>
      val myFoods   = foods.filter(_._1.playerId == playerId).map(_._2)
      val points    = myFoods.map(_.points).sum
      val finals    = collisions.filter(_._1.playerId == playerId).flatMap(_._2.finalScore)
      val peak      = (points +: finals).max
      val turns     = ctx.events.count(e => e.playerId == playerId && e.action == ChangeDirection)
      val intervals = directionChangesBetweenFood(ctx, playerId)

      val report = PlayerReport(
        playerId = playerId,
        metrics = Metrics.collect(
          Metrics.defined("foodEaten", myFoods.size.toDouble),
          Metrics.defined("totalPoints", points.toDouble),
          Metrics.defined("peakScore", peak.toDouble),
          Metrics.defined("directionChanges", turns.toDouble),
          "maxLength" -> myFoods.flatMap(_.currentLength).maxOption.map(_.toDouble)
        ),
        distributions = Map("foodTypes" -> Metrics.counts(myFoods)(_.foodType)),
        crossEventMetrics = Metrics.collect(
          Metrics.defined("foodIntervals", intervals.size.toDouble),
          "avgDirectionChangesBetweenFood" -> Metrics.mean(intervals.map(_.toDouble)),
          "bestDirectionChangesBetweenFood" -> intervals.minOption.map(_.toDouble)
        )
      )
      (report, peak)
    }

    GameAnalysis(
      players = rows.map(_._1),
      matchMetrics = Metrics.collect("peakScore" -> rows.map(_._2).maxOption.map(_.toDouble)),
      warnings = foodWarnings ++ collisionWarnings
    )

  /** Para cada par de EAT_FOOD sucesivos del jugador, cuántos CHANGE_DIRECTION hubo entre ellos.
    * Es un `foldLeft` puro con estado `Option[Int]`: `None` hasta la primera comida; luego el
    * contador de giros desde la última comida.
    */
  def directionChangesBetweenFood(ctx: MatchContext, playerId: String): Vector[Int] =
    ctx.events
      .filter(e => e.playerId == playerId && (e.action == EatFood || e.action == ChangeDirection))
      .foldLeft((Option.empty[Int], Vector.empty[Int])) { case ((sinceFood, intervals), event) =>
        if event.action == EatFood then (Some(0), sinceFood.fold(intervals)(intervals :+ _))
        else (sinceFood.map(_ + 1), intervals)
      }
      ._2
