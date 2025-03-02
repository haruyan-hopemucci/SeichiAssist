package com.github.unchama.seichiassist.minestack

case class MineStackObjectWithColorVariants(
  representative: MineStackObject,
  coloredVariants: List[MineStackObject]
) {
  require(coloredVariants.forall(_.category == representative.category))

  def category: MineStackObjectCategory = representative.category
}
