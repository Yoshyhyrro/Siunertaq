package $package$.z3

import com.microsoft.z3.*
import $package$.{ BSDVertex, BSDArrow, FVRole }

/** Z3 SMT ソルバを使ってバナッハノルム閾値制約を検証する。
  *
  * 方向付きバナッハ空間として見たクイバーのノルム制約:
  *   - Frobenius 矢印: ノルム単調増加  (tgt.norm >= src.norm)
  *   - Verschiebung 矢印: ノルム単調減少 (tgt.norm <= src.norm)
  *   - Dieudonné 関係: V∘F = [p]   (selmer.norm * affine.norm == p * leech.norm)
  */
object BanachConstraintSolver:

  /** 頂点ごとのデフォルト上界 (Golay weight から) */
  val defaultBounds: Map[BSDVertex, Double] = Map(
    BSDVertex.Leech      -> 0.0,
    BSDVertex.AffineDual -> 12.0,
    BSDVertex.Padic      -> 8.0,
    BSDVertex.Selmer     -> 16.0
  )

  /** 与えられた矢印リストがノルム制約を満たすか Z3 で確認する。
    *
    * @param arrows  検証対象の矢印群
    * @param prime   Dieudonné 関係の素数 p (デフォルト 7)
    * @return        充足可能なら Right(モデル文字列)、不充足なら Left(理由)
    */
  def verify(
    arrows: List[BSDArrow],
    prime:  Int = 7
  ): Either[String, String] =
    val ctx  = Context()
    val solver = ctx.mkSolver()

    // ノルム変数を頂点ごとに生成
    val normVars: Map[BSDVertex, RealExpr] =
      BSDVertex.values.map { v =>
        v -> ctx.mkRealConst(v.toString.toLowerCase)
      }.toMap

    // 非負制約
    normVars.values.foreach { r =>
      solver.add(ctx.mkGe(r, ctx.mkReal(0)))
    }

    // Frobenius / Verschiebung 方向制約
    arrows.foreach { arrow =>
      val src = normVars(arrow.src)
      val tgt = normVars(arrow.tgt)
      arrow.role match
        case FVRole.Frobenius    => solver.add(ctx.mkGe(tgt, src))
        case FVRole.Verschiebung => solver.add(ctx.mkLe(tgt, src))
    }

    // Dieudonné 関係: selmer.norm * affine.norm == p * leech.norm
    val selmer  = normVars(BSDVertex.Selmer)
    val affine  = normVars(BSDVertex.AffineDual)
    val leech   = normVars(BSDVertex.Leech)
    val pReal   = ctx.mkReal(prime)
    solver.add(
      ctx.mkEq(
        ctx.mkMul(selmer, affine),
        ctx.mkMul(pReal, leech)
      )
    )

    solver.check() match
      case Status.SATISFIABLE   => Right(solver.getModel.toString)
      case Status.UNSATISFIABLE => Left("UNSAT: ノルム制約が充足不可能")
      case _                    => Left("UNKNOWN: Z3 が結論を出せませんでした")
