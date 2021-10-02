package com.github.unchama.seichiassist.subsystems.chatratelimiter.domain

import cats.Monad
import cats.effect.concurrent.Ref
import cats.effect.{ConcurrentEffect, Sync, Timer}
import cats.implicits._
import com.github.unchama.datarepository.template.RepositoryDefinition.Phased.{SinglePhased, TwoPhased}
import com.github.unchama.datarepository.template.finalization.RepositoryFinalization
import com.github.unchama.datarepository.template.initialization.TwoPhasedRepositoryInitialization
import com.github.unchama.generic.ContextCoercion
import com.github.unchama.generic.ratelimiting.{FixedWindowRateLimiter, RateLimiter}
import com.github.unchama.minecraft.algebra.HasUuid
import com.github.unchama.seichiassist.subsystems.breakcount.BreakCountAPI

import scala.concurrent.duration.DurationInt

object ChatRateLimitRepositoryDefinition {
  def withContext[
    F[_] : ConcurrentEffect : Timer : Monad,
    G[_] : Sync : ContextCoercion[*[_], F] : Monad,
    Player : HasUuid
  ](implicit breakCountAPI: BreakCountAPI[F, G, Player]): TwoPhased[G, Player, Ref[G, Option[RateLimiter[G, ChatCount]]]] = {
    TwoPhased(
      TwoPhasedRepositoryInitialization.augment(
        SinglePhased.trivial[G, Player].initialization
      )((p, _) => for {
        sad <- breakCountAPI.seichiAmountDataRepository(p).read
        rateLimiter <- FixedWindowRateLimiter.in[F, G, ChatCount](ChatCount(1), 30.seconds)
        ref <- Ref[G].of(Option.when(sad.levelCorrespondingToExp.level == 1)(rateLimiter))
      } yield ref),
      // does not need any finalization
      RepositoryFinalization.trivial
    )
  }
}
