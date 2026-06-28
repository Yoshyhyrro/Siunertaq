
package io.siunertaq.batch

import cats.effect.unsafe.IORuntime
import io.siunertaq.expr.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// ─── PerlBridgeSpec ──────────────────────────────────────────────────────────
//
//  GADT consistency verification and Perl subprocess integration tests.
//
//  Unit tests (Perl not required):
//    §1  ProgramLifter.liftProgram  — Inverse of lowering
//    §2  ProgramLifter.liftTyped    — TypedResult (GADT dispatch)
//    §3  PerlBridge.generatePerl    — JSON-formatted script generation
//    §4  TypedParser typeclass      — Parse functions based on type T
//
//  Integration tests (requires RUN_PERL_CROSSCHECK=1 and perl in PATH):
//    §5  PerlBridge.runViaPerl      — Actual evaluation via Perl
//    §6  PerlBridge.crossCheck      — Scala vs Perl cross-verification

class PerlBridgeSpec extends AnyFunSpec with Matchers:

  given IORuntime = IORuntime.global

  // ── §1  ProgramLifter.liftProgram ──────────────────────────────────────────

  describe("ProgramLifter.liftProgram"):

    it("PushScalar → ConstScalar (single value)"):
      ProgramLifter.liftProgram(Vector(Instr.PushScalar(42))).shouldBe(
        Right(Expr.ConstScalar(42))
      )

    it("PushScalar × 2 + AddScalar → Add(l, r)"):
      val prog = Vector(Instr.PushScalar(3), Instr.PushScalar(4), Instr.AddScalar)
      ProgramLifter.liftProgram(prog).shouldBe(
        Right(Expr.Add(Expr.ConstScalar(3), Expr.ConstScalar(4)))
      )

    it("Lowering round-trip: liftProgram(lower(e)) == Right(e)"):
      val exprs = List(
        Expr.ConstScalar(7),
        Expr.Add(Expr.ConstScalar(2), Expr.ConstScalar(5)),
        Expr.Mul(Expr.ConstScalar(3), Expr.ConstScalar(4)),
        Expr.Dot(Expr.ConstVec3(1, 0, 0), Expr.ConstVec3(0, 1, 0)),
        Expr.Add(
          Expr.Mul(Expr.ConstScalar(2), Expr.ConstScalar(3)),
          Expr.Mul(Expr.ConstScalar(4), Expr.ConstScalar(5))
        )
      )
      for e <- exprs do
        val lowered = Lowering.lower(e).getOrElse(Vector.empty)
        ProgramLifter.liftProgram(lowered).shouldBe(Right(e))

    it("Empty program → stack empty (Left)"):
      ProgramLifter.liftProgram(Vector.empty).isLeft.shouldBe(true)

    it("Stack underflow (AddScalar, 0 values) → Left"):
      ProgramLifter.liftProgram(Vector(Instr.AddScalar)).isLeft.shouldBe(true)

  // ── §2  ProgramLifter.liftTyped — TypedResult (GADT dispatch) ──────────────

  describe("ProgramLifter.liftTyped"):

    it("Scalar program → ScalarTyped"):
      val prog = Vector(Instr.PushScalar(3), Instr.PushScalar(4), Instr.MulScalar)
      ProgramLifter.liftTyped(prog) match
        case Right(ProgramLifter.ScalarTyped(expr)) =>
          expr.shouldBe(TypedExpr.TMul(TypedExpr.TConstScalar(3), TypedExpr.TConstScalar(4)))
        case other => fail(s"Expected ScalarTyped, got $other")

    it("Vec3 program → Vec3Typed"):
      val prog = Vector(
        Instr.PushVec3(1, 2, 3),
        Instr.PushVec3(4, 5, 6),
        Instr.AddVec3             // AddVec3: Vec3 × Vec3 → Vec3 ✓
      )                           // (DotVec3 is Vec3 × Vec3 → Scalar, so not applicable)
      ProgramLifter.liftTyped(prog) match
        case Right(ProgramLifter.Vec3Typed(_)) => succeed
        case other => fail(s"Expected Vec3Typed, got $other")

    it("Dot(Vec3, Vec3) → ScalarTyped (DotVec3 returns Scalar)"):
      val prog = Vector(
        Instr.PushVec3(1, 0, 0),
        Instr.PushVec3(0, 1, 0),
        Instr.DotVec3
      )
      ProgramLifter.liftTyped(prog) match
        case Right(ProgramLifter.ScalarTyped(_)) => succeed
        case other => fail(s"Expected ScalarTyped for Dot result, got $other")

  // ── §3  PerlBridge.generatePerl — JSON-formatted script generation ──────────
  //
  //  New approach: Logic is consolidated in Siunertaq::StackMachine.pm.
  //  Generated scripts now simply pass the JSON from Program.toJson to execute_json.
  //  (Replaces old approach of inlining Perl like "push @stack, N;")

  describe("PerlBridge.generatePerl"):

    it("ensure 'use Siunertaq::StackMachine'"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(42)), "print_scalar")
      code.should(include("Siunertaq::StackMachine"))

    it("ensure call to execute_json"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(42)), "print_scalar")
      code.should(include("execute_json"))

    it("PushScalar(42) → JSON should contain \"PushScalar\" and 42"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(42)), "print_scalar")
      code.should(include("PushScalar"))
      code.should(include("42"))

    it("AddScalar → JSON should contain \"AddScalar\""):
      val code = PerlBridge.generatePerl(
        Vector(Instr.PushScalar(3), Instr.PushScalar(4), Instr.AddScalar),
        "print_scalar"
      )
      code.should(include("AddScalar"))
      code.should(include("3"))
      code.should(include("4"))

    it("PushVec3 + DotVec3 → JSON should contain corresponding keys"):
      val prog = Vector(Instr.PushVec3(1, 2, 3), Instr.PushVec3(4, 5, 6), Instr.DotVec3)
      val typedResult = ProgramLifter.liftTyped(prog)
        .getOrElse(ProgramLifter.ScalarTyped(TypedExpr.TConstScalar(0)))
      val pm = typedResult match
        case ProgramLifter.ScalarTyped(_) => "print_scalar"
        case ProgramLifter.Vec3Typed(_)   => "print_vec3"
      val code = PerlBridge.generatePerl(prog, pm)
      code.should(include("PushVec3"))
      code.should(include("DotVec3"))
      code.should(include("1"))
      code.should(include("2"))
      code.should(include("3"))

    it("printMethod should appear at the end"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(1)), "print_scalar")
      code.should(include("print_scalar"))

    it("include use strict / use warnings / FindBin"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(1)), "print_scalar")
      code.should(include("use strict"))
      code.should(include("use warnings"))
      code.should(include("FindBin"))

  // ── §4  TypedParser typeclass — Parse based on T ─────────────────────────────

  describe("TypedParser typeclass"):

    import ProgramLifter.{given, *}

    it("Scalar: '42\\n' → Right(ScalarValue(42))"):
      summon[TypedParser[Ty.Scalar.type]].parse("42\n").shouldBe(Right(ScalarValue(42)))

    it("Scalar: 'abc' → Left"):
      summon[TypedParser[Ty.Scalar.type]].parse("abc").isLeft.shouldBe(true)

    it("Vec3: '1 2 3' → Right(Vec3Value(1,2,3))"):
      summon[TypedParser[Ty.Vec3.type]].parse("1 2 3").shouldBe(
        Right(Vec3Value(1, 2, 3))
      )

    it("Vec3: '1 2' (insufficient values) → Left"):
      summon[TypedParser[Ty.Vec3.type]].parse("1 2").isLeft.shouldBe(true)

    it("parseTypedOutput: Use Scalar parser for ScalarTyped"):
      val typed = ScalarTyped(TypedExpr.TConstScalar(0))
      parseTypedOutput("99", typed).shouldBe(Right(ScalarValue(99)))

    it("parseTypedOutput: Use Vec3 parser for Vec3Typed"):
      val typed = Vec3Typed(TypedExpr.TConstVec3(0, 0, 0))
      parseTypedOutput("7 8 9", typed).shouldBe(Right(Vec3Value(7, 8, 9)))

    it("perlPrintFor: Scalar print expression for ScalarTyped"):
      val typed = ScalarTyped(TypedExpr.TConstScalar(0))
      perlPrintFor(typed).should(include("$stack[-1]"))
      perlPrintFor(typed).should(not(include("->")))

    it("perlPrintFor: Arrayref print expression for Vec3Typed"):
      val typed = Vec3Typed(TypedExpr.TConstVec3(0, 0, 0))
      perlPrintFor(typed).should(include("->[0]"))

  // ── §5 & §6  Integration (requires RUN_PERL_CROSSCHECK=1) ─────────────────

  private val PerlEnv = "RUN_PERL_CROSSCHECK"

  private def ensurePerl(): Unit =
    if !sys.env.get(PerlEnv).exists(v => v == "1" || v.equalsIgnoreCase("true")) then
      cancel(s"Set $PerlEnv=1 to run Perl integration tests")
    if !PerlBridge.isAvailable then
      fail(s"${PerlBridge.perlBin} not found on PATH (Strawberry Perl on Windows, system perl elsewhere)")

  describe("PerlBridge.runViaPerl (integration, requires Strawberry Perl)"):

    it("3 + 4 = 7  (Scalar AddScalar)"):
      ensurePerl()
      val prog = Vector(Instr.PushScalar(3), Instr.PushScalar(4), Instr.AddScalar)
      PerlBridge.runViaPerl(prog).unsafeRunSync().shouldBe(Right(ScalarValue(7)))

    it("3 * 4 = 12  (Scalar MulScalar)"):
      ensurePerl()
      val prog = Vector(Instr.PushScalar(3), Instr.PushScalar(4), Instr.MulScalar)
      PerlBridge.runViaPerl(prog).unsafeRunSync().shouldBe(Right(ScalarValue(12)))

    it("(2*3) + (4*5) = 26  (nested scalars)"):
      ensurePerl()
      val prog = Vector(
        Instr.PushScalar(2), Instr.PushScalar(3), Instr.MulScalar,
        Instr.PushScalar(4), Instr.PushScalar(5), Instr.MulScalar,
        Instr.AddScalar
      )
      PerlBridge.runViaPerl(prog).unsafeRunSync().shouldBe(Right(ScalarValue(26)))

    it("Vec3 AddVec3: (1,2,3)+(4,5,6)=(5,7,9)"):
      ensurePerl()
      val prog = Vector(
        Instr.PushVec3(1, 2, 3),
        Instr.PushVec3(4, 5, 6),
        Instr.AddVec3
      )
      PerlBridge.runViaPerl(prog).unsafeRunSync().shouldBe(Right(Vec3Value(5, 7, 9)))

    it("DotVec3: (1,2,3)·(4,5,6) = 4+10+18 = 32"):
      ensurePerl()
      val prog = Vector(
        Instr.PushVec3(1, 2, 3),
        Instr.PushVec3(4, 5, 6),
        Instr.DotVec3
      )
      PerlBridge.runViaPerl(prog).unsafeRunSync().shouldBe(Right(ScalarValue(32)))

  describe("PerlBridge.crossCheck (integration, requires Strawberry Perl)"):

    it("Scalar: crossCheck matches Scala evaluation"):
      ensurePerl()
      val prog = Vector(Instr.PushScalar(6), Instr.PushScalar(7), Instr.MulScalar)
      val crossResult = PerlBridge.crossCheck(prog).unsafeRunSync()
      val scalaResult = ProgramEval.exec(prog)
      crossResult.shouldBe(scalaResult)

    it("Vec3 Dot: crossCheck matches Scala evaluation"):
      ensurePerl()
      val prog = Vector(
        Instr.PushVec3(1, 2, 3),
        Instr.PushVec3(4, 5, 6),
        Instr.DotVec3
      )
      val crossResult = PerlBridge.crossCheck(prog).unsafeRunSync()
      val scalaResult = ProgramEval.exec(prog)
      crossResult.shouldBe(scalaResult)

    it("BatchJob.dhall frobenius-compile step: 12 * 1 = 12"):
      ensurePerl()
      val prog = Vector(Instr.PushScalar(12), Instr.PushScalar(1), Instr.MulScalar)
      val crossResult = PerlBridge.crossCheck(prog).unsafeRunSync()
      crossResult.shouldBe(Right(ScalarValue(12)))

    it("BatchJob.dhall padic-lower step: (2,4,0)·(1,0,8) = 2"):
      ensurePerl()
      val prog = Vector(
        Instr.PushVec3(2, 4, 0),
        Instr.PushVec3(1, 0, 8),
        Instr.DotVec3
      )
      val crossResult = PerlBridge.crossCheck(prog).unsafeRunSync()
      crossResult.shouldBe(Right(ScalarValue(2 * 1 + 4 * 0 + 0 * 8)))
