package io.siunertaq.z3

import com.microsoft.z3.*
import io.siunertaq.{ BSDVertex, BSDArrow }
import io.siunertaq.threshold.{ ThresholdConstraint, ThresholdNames, ThresholdProblem }

/** Verify Banach-norm threshold constraints using the Z3 SMT solver.
  *
  * Norm constraints on the quiver viewed as a directed Banach space:
  *   - Frobenius arrows:    tgt.norm >= src.norm  (norm monotone increasing)
  *   - Verschiebung arrows: tgt.norm <= src.norm  (norm monotone decreasing)
  *   - Dieudonné relation:  selmer.norm * affine.norm == p * leech.norm
  *
  * Example usage:
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
    verify(ThresholdProblem.fromArrows(arrows, prime))

  /** canonical threshold AST を Z3 で検証する。 */
  def verify(problem: ThresholdProblem): Either[String, String] =
    // try-with-resources 相当: Context は AutoCloseable
    val ctx    = Context()
    val solver = ctx.mkSolver()

    try
      // 頂点ごとのノルム変数
      val normVars: Map[BSDVertex, RealExpr] =
        BSDVertex.values.map { v =>
          v -> ctx.mkRealConst(ThresholdNames.normVar(v))
        }.toMap

      problem.constraints.foreach {
        case ThresholdConstraint.NonNegative(vertex) =>
          solver.add(ctx.mkGe(normVars(vertex), ctx.mkReal(0)))
        case ThresholdConstraint.EqualsConstant(vertex, value) =>
          solver.add(ctx.mkEq(normVars(vertex), ctx.mkReal(value)))
        case ThresholdConstraint.FrobeniusGE(src, tgt) =>
          solver.add(ctx.mkGe(normVars(tgt), normVars(src)))
        case ThresholdConstraint.VerschiebungLE(src, tgt) =>
          solver.add(ctx.mkLe(normVars(tgt), normVars(src)))
        case ThresholdConstraint.DieudonneEq(selmer, affine, prime, leech) =>
          solver.add(
            ctx.mkEq(
              ctx.mkMul(normVars(selmer), normVars(affine)),
              ctx.mkMul(ctx.mkReal(prime), normVars(leech))
            )
          )
      }

      val res = solver.check()
      if (res != null && res.equals(Status.SATISFIABLE))
        Right(solver.getModel.toString)
      else if (res != null && res.equals(Status.UNSATISFIABLE))
        Left("UNSAT: norm constraints are unsatisfiable")
      else
        Left("UNKNOWN: Z3 could not reach a conclusion")
    finally
      ctx.close()
