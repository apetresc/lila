package lila.round

import chess.Speed
import org.goochjs.glicko2._
import org.joda.time.DateTime
import play.api.Logger

import lila.game.{ GameRepo, Game, Pov, PerfPicker }
import lila.history.HistoryApi
import lila.rating.{ Glicko, Perf }
import lila.user.{ UserRepo, User, Perfs }

final class PerfsUpdater(historyApi: HistoryApi) {

  private val VOLATILITY = Glicko.default.volatility
  private val TAU = 0.75d
  private val system = new RatingCalculator(VOLATILITY, TAU)

  def save(game: Game, white: User, black: User, resetGameRatings: Boolean = false): Funit =
    PerfPicker.main(game) ?? { mainPerf =>
      (game.rated && game.finished && game.accountable && !white.engine && !black.engine) ?? {
        val ratingsW = mkRatings(white.perfs, game.poolId)
        val ratingsB = mkRatings(black.perfs, game.poolId)
        val result = resultOf(game)
        game.variant match {
          case chess.Variant.Chess960 =>
            updateRatings(ratingsW.chess960, ratingsB.chess960, result, system)
          case chess.Variant.KingOfTheHill =>
            updateRatings(ratingsW.kingOfTheHill, ratingsB.kingOfTheHill, result, system)
          case chess.Variant.ThreeCheck =>
            updateRatings(ratingsW.threeCheck, ratingsB.threeCheck, result, system)
          case _ =>
        }
        if (game.variant.standard) {
          game.speed match {
            case chess.Speed.Bullet =>
              updateRatings(ratingsW.bullet, ratingsB.bullet, result, system)
            case chess.Speed.Blitz =>
              updateRatings(ratingsW.blitz, ratingsB.blitz, result, system)
            case chess.Speed.Classical | chess.Speed.Unlimited =>
              updateRatings(ratingsW.classical, ratingsB.classical, result, system)
          }
        }
        (ratingsW.pool |@| ratingsB.pool) apply {
          case ((_, prW), (_, prB)) => updateRatings(prW, prB, result, system)
        }
        val perfsW = mkPerfs(ratingsW, white.perfs, game)
        val perfsB = mkPerfs(ratingsB, black.perfs, game)
        def intRatingLens(perfs: Perfs) = mainPerf(perfs).glicko.intRating
        resetGameRatings.fold(
          GameRepo.setRatingAndDiffs(game.id,
            intRatingLens(white.perfs) -> (intRatingLens(perfsW) - intRatingLens(white.perfs)),
            intRatingLens(black.perfs) -> (intRatingLens(perfsB) - intRatingLens(black.perfs))),
          GameRepo.setRatingDiffs(game.id,
            intRatingLens(perfsW) - intRatingLens(white.perfs),
            intRatingLens(perfsB) - intRatingLens(black.perfs))
        ) zip
          UserRepo.setPerfs(white, perfsW, white.perfs) zip
          UserRepo.setPerfs(black, perfsB, black.perfs) zip
          historyApi.add(white, game, perfsW) zip
          historyApi.add(black, game, perfsB)
      }.void
    }

  private final case class Ratings(
    chess960: Rating,
    kingOfTheHill: Rating,
    threeCheck: Rating,
    bullet: Rating,
    blitz: Rating,
    classical: Rating,
    pool: Option[(String, Rating)])

  private def mkRatings(perfs: Perfs, poolId: Option[String]) = new Ratings(
    chess960 = perfs.chess960.toRating,
    kingOfTheHill = perfs.kingOfTheHill.toRating,
    threeCheck = perfs.threeCheck.toRating,
    bullet = perfs.bullet.toRating,
    blitz = perfs.blitz.toRating,
    classical = perfs.classical.toRating,
    pool = poolId map (id => id -> perfs.pool(id).toRating))

  private def resultOf(game: Game): Glicko.Result =
    game.winnerColor match {
      case Some(chess.White) => Glicko.Result.Win
      case Some(chess.Black) => Glicko.Result.Loss
      case None              => Glicko.Result.Draw
    }

  private def updateRatings(white: Rating, black: Rating, result: Glicko.Result, system: RatingCalculator) {
    val results = new RatingPeriodResults()
    result match {
      case Glicko.Result.Draw => results.addDraw(white, black)
      case Glicko.Result.Win  => results.addResult(white, black)
      case Glicko.Result.Loss => results.addResult(black, white)
    }
    try {
      system.updateRatings(results)
    }
    catch {
      case e: Exception => logger.error(e.getMessage)
    }
  }

  private val classicalSpeeds: Set[Speed] = Set(Speed.Classical, Speed.Unlimited)

  private def mkPerfs(ratings: Ratings, perfs: Perfs, game: Game): Perfs = {
    val speed = game.speed
    val isStd = game.variant.standard
    val date = game.updatedAt | game.createdAt
    val perfs1 = perfs.copy(
      chess960 = game.variant.chess960.fold(perfs.chess960.add(ratings.chess960, date), perfs.chess960),
      kingOfTheHill = game.variant.kingOfTheHill.fold(perfs.kingOfTheHill.add(ratings.kingOfTheHill, date), perfs.kingOfTheHill),
      threeCheck = game.variant.threeCheck.fold(perfs.threeCheck.add(ratings.threeCheck, date), perfs.threeCheck),
      bullet = (isStd && speed == Speed.Bullet).fold(perfs.bullet.add(ratings.bullet, date), perfs.bullet),
      blitz = (isStd && speed == Speed.Blitz).fold(perfs.blitz.add(ratings.blitz, date), perfs.blitz),
      classical = (isStd && classicalSpeeds(speed)).fold(perfs.classical.add(ratings.classical, date), perfs.classical))
    val perfs2 = if (isStd) perfs1.updateStandard else perfs1
    ratings.pool.fold(perfs2) {
      case (id, poolRating) => perfs2.copy(
        pools = perfs2.pools + (id -> perfs.pool(id).add(poolRating, date))
      )
    }
  }

  private def logger = play.api.Logger("PerfsUpdater")
}
