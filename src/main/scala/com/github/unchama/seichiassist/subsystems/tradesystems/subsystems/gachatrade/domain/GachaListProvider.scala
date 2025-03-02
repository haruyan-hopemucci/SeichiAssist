package com.github.unchama.seichiassist.subsystems.tradesystems.subsystems.gachatrade.domain

import com.github.unchama.seichiassist.subsystems.gacha.domain.gachaprize.GachaPrize

trait GachaListProvider[F[_], ItemStack] {
  def readGachaList: F[Vector[GachaPrize[ItemStack]]]
}
