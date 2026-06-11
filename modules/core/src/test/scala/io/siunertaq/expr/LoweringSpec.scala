package io.siunertaq.expr

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class LoweringSpec extends AnyFunSpec with Matchers:

  describe("ExprEval") {
    it("evaluates ConstScalar") {
      ExprEval.eval(Expr.ConstScalar(7)) shouldBe Right(ScalarValue(7))
    }
    it("evaluates ConstVec3") {
      ExprEval.eval(Expr.ConstVec3(1, 2, 3)) shouldBe Right(Vec3Value(1, 2, 3))
    }
    it("evaluates Add of scalars") {
      ExprEval.eval(Expr.Add(Expr.ConstScalar(3), Expr.ConstScalar(4))) shouldBe Right(ScalarValue(7))
    }
    it("evaluates Add of vec3") {
      val e = Expr.Add(Expr.ConstVec3(1, 0, 0), Expr.ConstVec3(0, 1, 0))
      ExprEval.eval(e) shouldBe Right(Vec3Value(1, 1, 0))
    }
    it("evaluates Mul of scalars") {
      ExprEval.eval(Expr.Mul(Expr.ConstScalar(3), Expr.ConstScalar(4))) shouldBe Right(ScalarValue(12))
    }
    it("evaluates Dot") {
      val e = Expr.Dot(Expr.ConstVec3(1, 2, 3), Expr.ConstVec3(4, 5, 6))
      ExprEval.eval(e) shouldBe Right(ScalarValue(1 * 4 + 2 * 5 + 3 * 6))
    }
    it("Dot is symmetric: eval(Dot(a,b)) == eval(Dot(b,a))") {
      val a = Expr.ConstVec3(1, 2, 3)
      val b = Expr.ConstVec3(4, 5, 6)
      ExprEval.eval(Expr.Dot(a, b)) shouldBe ExprEval.eval(Expr.Dot(b, a))
    }
  }

  describe("Lowering preserves semantics: exec(lower(e)) == eval(e)") {
    def check(e: Expr): Unit =
      val evalResult  = ExprEval.eval(e)
      val lowerResult = Lowering.lower(e).flatMap(ProgramEval.exec(_))
      (lowerResult shouldBe evalResult):Unit

    it("ConstScalar")           { check(Expr.ConstScalar(42)) }
    it("ConstVec3")             { check(Expr.ConstVec3(1, 2, 3)) }
    it("Add scalars")           { check(Expr.Add(Expr.ConstScalar(3), Expr.ConstScalar(4))) }
    it("Add vec3")              { check(Expr.Add(Expr.ConstVec3(1, 0, 0), Expr.ConstVec3(0, 1, 0))) }
    it("Mul scalars")           { check(Expr.Mul(Expr.ConstScalar(6), Expr.ConstScalar(7))) }
    it("Dot")                   { check(Expr.Dot(Expr.ConstVec3(1, 2, 3), Expr.ConstVec3(4, 5, 6))) }
    it("nested: Add(Mul, Mul)") {
      check(Expr.Add(
        Expr.Mul(Expr.ConstScalar(2), Expr.ConstScalar(3)),
        Expr.Mul(Expr.ConstScalar(4), Expr.ConstScalar(5))
      ))
    }
    it("nested: Dot with Add operands") {
      check(Expr.Dot(
        Expr.Add(Expr.ConstVec3(1, 0, 0), Expr.ConstVec3(0, 1, 0)),
        Expr.ConstVec3(1, 1, 1)
      ))
    }
  }

  describe("SExpr round-trip: fromSExpr(toSExpr(e)) == Right(e)") {
    def roundTrip(e: Expr): Unit =
  (SExpr.fromSExpr(SExpr.toSExpr(e)) shouldBe Right(e)): Unit

    it("ConstScalar")  { roundTrip(Expr.ConstScalar(99)) }
    it("ConstVec3")    { roundTrip(Expr.ConstVec3(3, 1, 4)) }
    it("Add")          { roundTrip(Expr.Add(Expr.ConstScalar(1), Expr.ConstScalar(2))) }
    it("Mul")          { roundTrip(Expr.Mul(Expr.ConstScalar(3), Expr.ConstScalar(4))) }
    it("Dot")          { roundTrip(Expr.Dot(Expr.ConstVec3(1, 0, 0), Expr.ConstVec3(0, 0, 1))) }
    it("nested") {
      roundTrip(Expr.Dot(
        Expr.Add(Expr.ConstVec3(1, 0, 0), Expr.ConstVec3(0, 1, 0)),
        Expr.ConstVec3(1, 1, 1)
      ))
    }
  }

  describe("SExpr prettyPrint") {
    it("formats Dot expression") {
      val e = Expr.Dot(Expr.ConstVec3(1, 2, 3), Expr.ConstVec3(4, 5, 6))
      SExpr.prettyPrint(SExpr.toSExpr(e)) shouldBe
        "(Dot (ConstVec3 1 2 3) (ConstVec3 4 5 6))"
    }
  }
