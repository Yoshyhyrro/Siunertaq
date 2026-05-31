package $package$

/** BSD クイバーの頂点 (Lean の BSDVertex に対応) */
enum BSDVertex:
  case Leech       // z(Λ₂₄)  ノルム境界 0
  case AffineDual  // √A₁₁∨   ノルム境界 12
  case Padic       // O^p      ノルム境界 8
  case Selmer      // Σ_I      ノルム境界 16

/** Frobenius / Verschiebung 分類 */
enum FVRole:
  case Frobenius    // ノルム増加方向 (前向き依存 = ビルド実行)
  case Verschiebung // ノルム減少方向 (後向き依存 = キャッシュ無効化)

/** BSD クイバーの矢印 (Lean の BSDArrow に対応)
  *
  * @param src    始点頂点
  * @param tgt    終点頂点
  * @param role   Frobenius か Verschiebung か
  * @param effect Z3 検証済みの閾値を通過した後に実行する IO 効果
  */
final case class BSDArrow(
  src:    BSDVertex,
  tgt:    BSDVertex,
  role:   FVRole,
  effect: cats.effect.IO[Unit]
)

/** Shake の Rule に相当するバナッハルール */
final case class BanachRule(
  key:       BSDVertex,
  normBound: Double,         // Z3 で検証されたノルム上界
  deps:      List[BSDArrow],
  action:    cats.effect.IO[Unit]
)

object BSDArrow:
  import BSDVertex.*
  import FVRole.*
  import cats.effect.IO

  // 標準的な 4 矢印 (効果は後で差し替え)
  def tensorBang(eff: IO[Unit])      = BSDArrow(Leech,      AffineDual, Frobenius,    eff)
  def oplusPadic(eff: IO[Unit])      = BSDArrow(AffineDual, Padic,      Frobenius,    eff)
  def projectSelmer(eff: IO[Unit])   = BSDArrow(Leech,      Selmer,     Verschiebung, eff)
  def recover(eff: IO[Unit])         = BSDArrow(Selmer,     AffineDual, Verschiebung, eff)
