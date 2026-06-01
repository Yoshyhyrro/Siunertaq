package io.siunertaq

import cats.effect.IO

/** Vertices of the BSD quiver (corresponding to Lean's `BSDVertex`)
  *
  * Scala 3.8: `enum` + `derives CanEqual` for SIP-67 strict-equality support
  */
enum BSDVertex derives CanEqual:
  case Leech       // z(Λ₂₄)  ノルム境界 0
  case AffineDual  // √A₁₁∨   ノルム境界 12
  case Padic       // O^p      ノルム境界 8
  case Selmer      // Σ_I      ノルム境界 16

/** Directional roles: Frobenius / Verschiebung */
enum FVRole derives CanEqual:
  case Frobenius    // ノルム増加方向 (前向き依存 = ビルド実行、Siunertaq)
  case Verschiebung // ノルム減少方向 (後向き依存 = キャッシュ無効化)

/** Arrows of the BSD quiver (corresponding to Lean's `BSDArrow`)
  *
  * @param src    source vertex
  * @param tgt    target vertex
  * @param role   Frobenius or Verschiebung
  * @param effect IO effect to run after passing a Z3-verified threshold
  */
final case class BSDArrow(
  src:    BSDVertex,
  tgt:    BSDVertex,
  role:   FVRole,
  effect: IO[Unit]
)

/** Banach rule, analogous to a Haskell Shake `Rule` */
final case class BanachRule(
  key:       BSDVertex,
  normBound: Double,          // Norm upper bound verified by Z3
  deps:      List[BSDArrow],
  action:    IO[Unit]
)

object BSDArrow:
  import BSDVertex.*
  import FVRole.*

  // Standard 4-arrow factories (effects can be substituted later)
  // Frobenius: tensor_bang (Leech → AffineDual)
  def tensorBang(eff: IO[Unit])    = BSDArrow(Leech,      AffineDual, Frobenius,    eff)
  // Frobenius: oplus_padic (AffineDual → Padic)
  def oplusPadic(eff: IO[Unit])    = BSDArrow(AffineDual, Padic,      Frobenius,    eff)
  // Verschiebung: project_selmer (Leech → Selmer)
  def projectSelmer(eff: IO[Unit]) = BSDArrow(Leech,      Selmer,     Verschiebung, eff)
  // Verschiebung: recover (Selmer → AffineDual)
  def recover(eff: IO[Unit])       = BSDArrow(Selmer,     AffineDual, Verschiebung, eff)
