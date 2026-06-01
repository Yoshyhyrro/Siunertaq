package io.siunertaq

import cats.effect.IO

/** BSD クイバーの頂点 (Lean の BSDVertex に対応)
  *
  * Scala 3.8: enum + derives CanEqual で SIP-67 厳格等値に対応
  */
enum BSDVertex derives CanEqual:
  case Leech       // z(Λ₂₄)  ノルム境界 0
  case AffineDual  // √A₁₁∨   ノルム境界 12
  case Padic       // O^p      ノルム境界 8
  case Selmer      // Σ_I      ノルム境界 16

/** Frobenius / Verschiebung の方向分類 */
enum FVRole derives CanEqual:
  case Frobenius    // ノルム増加方向 (前向き依存 = ビルド実行、Siunertaq)
  case Verschiebung // ノルム減少方向 (後向き依存 = キャッシュ無効化)

/** BSD クイバーの矢印 (Lean の BSDArrow に対応)
  *
  * @param src    始点頂点
  * @param tgt    終点頂点
  * @param role   Frobenius か Verschiebung か
  * @param effect Z3 検証済み閾値を通過した後に実行する IO 効果
  */
final case class BSDArrow(
  src:    BSDVertex,
  tgt:    BSDVertex,
  role:   FVRole,
  effect: IO[Unit]
)

/** Haskell Shake の Rule に相当するバナッハルール */
final case class BanachRule(
  key:       BSDVertex,
  normBound: Double,          // Z3 で検証されたノルム上界
  deps:      List[BSDArrow],
  action:    IO[Unit]
)

object BSDArrow:
  import BSDVertex.*
  import FVRole.*

  // 標準的な 4 矢印ファクトリ (効果は後で差し替え)
  // Frobenius: tensor_bang (Leech → AffineDual)
  def tensorBang(eff: IO[Unit])    = BSDArrow(Leech,      AffineDual, Frobenius,    eff)
  // Frobenius: oplus_padic (AffineDual → Padic)
  def oplusPadic(eff: IO[Unit])    = BSDArrow(AffineDual, Padic,      Frobenius,    eff)
  // Verschiebung: project_selmer (Leech → Selmer)
  def projectSelmer(eff: IO[Unit]) = BSDArrow(Leech,      Selmer,     Verschiebung, eff)
  // Verschiebung: recover (Selmer → AffineDual)
  def recover(eff: IO[Unit])       = BSDArrow(Selmer,     AffineDual, Verschiebung, eff)
