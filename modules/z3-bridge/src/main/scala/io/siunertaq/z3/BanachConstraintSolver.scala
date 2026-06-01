package io.siunertaq.z3

import com.microsoft.z3.*
import io.siunertaq.{ BSDVertex, BSDArrow, FVRole }

/** Z3 SMT ソルバでバナッハノルム閾値制約を検証する。
  *
  * 方向付きバナッハ空間として見たクイバーのノルム制約:
  *   - Frobenius 矢印:    tgt.norm >= src.norm  (ノルム単調増加)
  *   - Verschiebung 矢印: tgt.norm <= src.norm  (ノルム単調減少)
  *   - Dieudonné 関係:    selmer.norm * affine.norm == p * leech.norm
  *
  * 使用例:
  * {{{
  *   val arrows = List(
  *     BSDArrow.tensorBang(IO.unit),
  *     BSDArrow.projectSelmer(IO.unit)
  *   )
  *   BanachConstraintSolver.verify(arrows) match
  *     case Right(model) => println(s"SAT: $model")
  *     case Left(reason) => println(s"UNSAT: $reason")
  * }}}
  */
object BanachConstraintSolver:

  /** Z3 Context は重いので使い捨てにする (スレッドセーフでないため) */
  def verify(
    arrows: List[BSDArrow],
    prime:  Int = 7
  ): Either[String, String] =
    // try-with-resources 相当: Context は AutoCloseable
    val ctx    = Context()
    val solver = ctx.mkSolver()

    try
      // 頂点ごとのノルム変数
      val normVars: Map[BSDVertex, RealExpr] =
        BSDVertex.values.map { v =>
          v -> ctx.mkRealConst(v.toString.toLowerCase)
        }.toMap

      // 非負制約
      normVars.values.foreach { r =>
        solver.add(ctx.mkGe(r, ctx.mkReal(0)))
      }

      // Frobenius: ノルム増加 / Verschiebung: ノルム減少
      arrows.foreach { arrow =>
        val src = normVars(arrow.src)
        val tgt = normVars(arrow.tgt)
        arrow.role match
          case FVRole.Frobenius    => solver.add(ctx.mkGe(tgt, src))
          case FVRole.Verschiebung => solver.add(ctx.mkLe(tgt, src))
      }

      // Dieudonné 関係: V∘F = [p]
      // selmer.norm * affine.norm == p * leech.norm
      val selmer = normVars(BSDVertex.Selmer)
      val affine = normVars(BSDVertex.AffineDual)
      val leech  = normVars(BSDVertex.Leech)
      solver.add(
        ctx.mkEq(
          ctx.mkMul(selmer, affine),
          ctx.mkMul(ctx.mkReal(prime), leech)
        )
      )

      val res = solver.check()
      if (res != null && res.equals(Status.SATISFIABLE))
        Right(solver.getModel.toString)
      else if (res != null && res.equals(Status.UNSATISFIABLE))
        Left("UNSAT: ノルム制約が充足不可能")
      else
        Left("UNKNOWN: Z3 が結論を出せませんでした")
    finally
      ctx.close()
