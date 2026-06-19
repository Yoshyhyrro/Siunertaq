package io.siunertaq.threshold

import cats.effect.IO
import io.siunertaq.BSDArrow
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// Placement: modules/core/src/test/scala/io/siunertaq/threshold/ThresholdSExprSpec.scala

class ThresholdSExprSpec extends AnyFunSpec with Matchers:

  import io.siunertaq.BSDVertex

  describe("ThresholdProblem.fromArrows") {
    it("adds non-negative, monotonic, and Dieudonne constraints") {
      val problem = ThresholdProblem.fromArrows(
        List(BSDArrow.tensorBang(IO.unit), BSDArrow.projectSelmer(IO.unit)),
        prime = 11
      )

      // Dotted method syntax: avoids Scala 3.8 alphanumeric-infix warning
      problem.constraints.should(contain(ThresholdConstraint.NonNegative(BSDVertex.Leech)))
      problem.constraints.should(contain(ThresholdConstraint.FrobeniusGE(BSDVertex.Leech, BSDVertex.AffineDual)))
      problem.constraints.should(contain(ThresholdConstraint.VerschiebungLE(BSDVertex.Leech, BSDVertex.Selmer)))
      problem.constraints.should(contain(ThresholdConstraint.DieudonneEq(BSDVertex.Selmer, BSDVertex.AffineDual, 11, BSDVertex.Leech)))
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

      ThresholdSExprCodec.fromSExpr(ThresholdSExprCodec.toSExpr(problem))
        .shouldBe(Right(problem))
    }
  }

  describe("ThresholdSExpr prettyPrint") {
    it("renders canonical constraint names") {
      val problem = ThresholdProblem(
        ThresholdProblem.fromArrows(List(BSDArrow.tensorBang(IO.unit)), prime = 7).constraints :+
          ThresholdConstraint.EqualsConstant(BSDVertex.Padic, 8)
      )

      val rendered = ThresholdSExprCodec.prettyPrint(ThresholdSExprCodec.toSExpr(problem))
      rendered.should(include("FrobeniusGE"))
      rendered.should(include("DieudonneEq"))
      rendered.should(include("EqualsConstant"))
    }
  }