package io.siunertaq.threshold

import cats.effect.IO
import io.siunertaq.BSDArrow
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class ThresholdSExprSpec extends AnyFunSpec with Matchers:

  import io.siunertaq.BSDVertex

  describe("ThresholdProblem.fromArrows") {
    it("adds non-negative, monotonic, and Dieudonne constraints") {
      val problem = ThresholdProblem.fromArrows(
        List(BSDArrow.tensorBang(IO.unit), BSDArrow.projectSelmer(IO.unit)),
        prime = 11
      )

      problem.constraints should contain (ThresholdConstraint.NonNegative(io.siunertaq.BSDVertex.Leech))
      problem.constraints should contain (ThresholdConstraint.FrobeniusGE(io.siunertaq.BSDVertex.Leech, io.siunertaq.BSDVertex.AffineDual))
      problem.constraints should contain (ThresholdConstraint.VerschiebungLE(io.siunertaq.BSDVertex.Leech, io.siunertaq.BSDVertex.Selmer))
      problem.constraints should contain (ThresholdConstraint.DieudonneEq(io.siunertaq.BSDVertex.Selmer, io.siunertaq.BSDVertex.AffineDual, 11, io.siunertaq.BSDVertex.Leech))
    }
  }

  describe("ThresholdSExpr round-trip") {
    it("round-trips a threshold problem through canonical S-expressions") {
      val problem = ThresholdProblem(
        ThresholdProblem.fromArrows(
          List(
            BSDArrow.tensorBang(IO.unit),
            BSDArrow.oplusPadic(IO.unit),
            BSDArrow.projectSelmer(IO.unit),
            BSDArrow.recover(IO.unit)
          ),
          prime = 7
        ).constraints :+ ThresholdConstraint.EqualsConstant(BSDVertex.Padic, 8)
      )

      ThresholdSExprCodec.fromSExpr(ThresholdSExprCodec.toSExpr(problem)) shouldBe Right(problem)
    }
  }

  describe("ThresholdSExpr prettyPrint") {
    it("renders canonical constraint names") {
      val problem = ThresholdProblem(
        ThresholdProblem.fromArrows(List(BSDArrow.tensorBang(IO.unit)), prime = 7).constraints :+
          ThresholdConstraint.EqualsConstant(BSDVertex.Padic, 8)
      )

      ThresholdSExprCodec.prettyPrint(ThresholdSExprCodec.toSExpr(problem)) should include ("FrobeniusGE")
      ThresholdSExprCodec.prettyPrint(ThresholdSExprCodec.toSExpr(problem)) should include ("DieudonneEq")
      ThresholdSExprCodec.prettyPrint(ThresholdSExprCodec.toSExpr(problem)) should include ("EqualsConstant")
    }
  }