package io.siunertaq.yices

import cats.effect.IO
import io.siunertaq.BSDArrow
import io.siunertaq.BSDVertex
import io.siunertaq.threshold.ThresholdConstraint
import io.siunertaq.threshold.ThresholdProblem
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class YicesThresholdSolverSpec extends AnyFunSpec with Matchers:

  describe("YicesSmtLib") {
    it("renders canonical SMT-LIB2 for threshold constraints") {
      val problem = ThresholdProblem.fromArrows(
        List(BSDArrow.tensorBang(IO.unit), BSDArrow.projectSelmer(IO.unit)),
        prime = 13
      )

      val smt = YicesSmtLib.renderCheck(problem)
      smt should include ("(set-logic QF_NRA)")
      smt should include ("(declare-fun leech () Real)")
      smt should include ("(assert (>= affinedual leech))")
      smt should include ("(assert (<= selmer leech))")
      smt should include ("(* 13 leech)")
      smt should endWith ("(check-sat)")
    }

    it("renders constant equalities for solver fixtures") {
      val problem = ThresholdProblem(Vector(ThresholdConstraint.EqualsConstant(BSDVertex.Leech, 1)))

      YicesSmtLib.renderCheck(problem) should include ("(assert (= leech 1))")
    }

    it("renders model requests separately") {
      val problem = ThresholdProblem.fromArrows(List(BSDArrow.tensorBang(IO.unit)))
      YicesSmtLib.renderModel(problem) should include ("(get-model)")
    }
  }

  describe("YicesThresholdSolver.parseStatus") {
    it("accepts sat/unsat/unknown") {
      YicesThresholdSolver.parseStatus("sat\n") shouldBe Right(YicesStatus.Sat)
      YicesThresholdSolver.parseStatus("unsat\n") shouldBe Right(YicesStatus.Unsat)
      YicesThresholdSolver.parseStatus("unknown\n") shouldBe Right(YicesStatus.Unknown)
    }

    it("rejects empty output") {
      YicesThresholdSolver.parseStatus("   \n") shouldBe Left("Received empty response from Yices 2")
    }
  }