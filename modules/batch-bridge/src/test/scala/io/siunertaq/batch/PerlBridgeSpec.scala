package io.siunertaq.batch

import cats.effect.unsafe.IORuntime
import io.siunertaq.expr.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// ─── PerlBridgeSpec ──────────────────────────────────────────────────────────
//
//  GADTとの整合検証 + Perl subprocess 統合テスト。
//
//  Unit テスト (Perl 不要):
//    §1  ProgramLifter.liftProgram  — Lowering の逆
//    §2  ProgramLifter.liftTyped    — TypedResult (GADT dispatch)
//    §3  PerlBridge.generatePerl    — JSON 形式スクリプト生成
//    §4  TypedParser typeclass      — T に応じた parse 関数
//
//  Integration テスト (RUN_PERL_CROSSCHECK=1 かつ perl が PATH 上にある場合):
//    §5  PerlBridge.runViaPerl      — Perl で実際に評価
//    §6  PerlBridge.crossCheck      — Scala == Perl 検証

class PerlBridgeSpec extends AnyFunSpec with Matchers:

  given IORuntime = IORuntime.global

  // ── §1  ProgramLifter.liftProgram ──────────────────────────────────────────

  describe("ProgramLifter.liftProgram"):

    it("PushScalar → ConstScalar (単値)"):
      ProgramLifter.liftProgram(Vector(Instr.PushScalar(42))).shouldBe(
        Right(Expr.ConstScalar(42))
      )

    it("PushScalar × 2 + AddScalar → Add(l, r)"):
      val prog = Vector(Instr.PushScalar(3), Instr.PushScalar(4), Instr.AddScalar)
      ProgramLifter.liftProgram(prog).shouldBe(
        Right(Expr.Add(Expr.ConstScalar(3), Expr.ConstScalar(4)))
      )

    it("Lowering ラウンドトリップ: liftProgram(lower(e)) == Right(e)"):
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

    it("空プログラム → スタックに値が残らない (Left)"):
      ProgramLifter.liftProgram(Vector.empty).isLeft.shouldBe(true)

    it("スタック不足 (AddScalar, 0 values) → Left"):
      ProgramLifter.liftProgram(Vector(Instr.AddScalar)).isLeft.shouldBe(true)

  // ── §2  ProgramLifter.liftTyped — TypedResult (GADT dispatch) ──────────────

  describe("ProgramLifter.liftTyped"):

    it("Scalar プログラム → ScalarTyped"):
      val prog = Vector(Instr.PushScalar(3), Instr.PushScalar(4), Instr.MulScalar)
      ProgramLifter.liftTyped(prog) match
        case Right(ProgramLifter.ScalarTyped(expr)) =>
          expr.shouldBe(TypedExpr.TMul(TypedExpr.TConstScalar(3), TypedExpr.TConstScalar(4)))
        case other => fail(s"Expected ScalarTyped, got $other")

    it("Vec3 プログラム → Vec3Typed"):
      val prog = Vector(
        Instr.PushVec3(1, 2, 3),
        Instr.PushVec3(4, 5, 6),
        Instr.AddVec3             // AddVec3: Vec3 × Vec3 → Vec3 ✓
      )                           // (DotVec3 は Vec3 × Vec3 → Scalar なので不可)
      ProgramLifter.liftTyped(prog) match
        case Right(ProgramLifter.Vec3Typed(_)) => succeed
        case other => fail(s"Expected Vec3Typed, got $other")

    it("Dot(Vec3, Vec3) → ScalarTyped (DotVec3 は Scalar を返す)"):
      val prog = Vector(
        Instr.PushVec3(1, 0, 0),
        Instr.PushVec3(0, 1, 0),
        Instr.DotVec3
      )
      ProgramLifter.liftTyped(prog) match
        case Right(ProgramLifter.ScalarTyped(_)) => succeed
        case other => fail(s"Expected ScalarTyped for Dot result, got $other")

  // ── §3  PerlBridge.generatePerl — JSON 形式スクリプト生成 ─────────────────
  //
  //  新方式: ロジックは Siunertaq::StackMachine.pm に集約し、
  //  生成スクリプトは Program.toJson の JSON を execute_json に渡すだけ。
  //  (旧方式の "push @stack, N;" 等のインライン Perl は不在)

  describe("PerlBridge.generatePerl"):

    it("Siunertaq::StackMachine を use する"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(42)), "print_scalar")
      code.should(include("Siunertaq::StackMachine"))

    it("execute_json を呼び出す"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(42)), "print_scalar")
      code.should(include("execute_json"))

    it("PushScalar(42) → JSON に \"PushScalar\" と 42 が含まれる"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(42)), "print_scalar")
      code.should(include("PushScalar"))
      code.should(include("42"))

    it("AddScalar → JSON に \"AddScalar\" が含まれる"):
      val code = PerlBridge.generatePerl(
        Vector(Instr.PushScalar(3), Instr.PushScalar(4), Instr.AddScalar),
        "print_scalar"
      )
      code.should(include("AddScalar"))
      code.should(include("3"))
      code.should(include("4"))

    it("PushVec3 + DotVec3 → JSON に対応キーが含まれる"):
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

    it("printMethod が末尾に現れる"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(1)), "print_scalar")
      code.should(include("print_scalar"))

    it("use strict / use warnings / FindBin が含まれる"):
      val code = PerlBridge.generatePerl(Vector(Instr.PushScalar(1)), "print_scalar")
      code.should(include("use strict"))
      code.should(include("use warnings"))
      code.should(include("FindBin"))

  // ── §4  TypedParser typeclass — T に応じた parse ─────────────────────────

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

    it("Vec3: '1 2' (不足) → Left"):
      summon[TypedParser[Ty.Vec3.type]].parse("1 2").isLeft.shouldBe(true)

    it("parseTypedOutput: ScalarTyped → Scalar パーサーを使用"):
      val typed = ScalarTyped(TypedExpr.TConstScalar(0))
      parseTypedOutput("99", typed).shouldBe(Right(ScalarValue(99)))

    it("parseTypedOutput: Vec3Typed → Vec3 パーサーを使用"):
      val typed = Vec3Typed(TypedExpr.TConstVec3(0, 0, 0))
      parseTypedOutput("7 8 9", typed).shouldBe(Right(Vec3Value(7, 8, 9)))

    it("perlPrintFor: ScalarTyped → スカラ用 print 式"):
      val typed = ScalarTyped(TypedExpr.TConstScalar(0))
      perlPrintFor(typed).should(include("$stack[-1]"))
      perlPrintFor(typed).should(not(include("->")))

    it("perlPrintFor: Vec3Typed → arrayref 用 print 式"):
      val typed = Vec3Typed(TypedExpr.TConstVec3(0, 0, 0))
      perlPrintFor(typed).should(include("->[0]"))

  // ── §5 & §6  Integration (RUN_PERL_CROSSCHECK=1 が必要) ─────────────────

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

    it("(2*3) + (4*5) = 26  (ネスト Scalar)"):
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

    it("Scalar: crossCheck == Scala 評価結果"):
      ensurePerl()
      val prog = Vector(Instr.PushScalar(6), Instr.PushScalar(7), Instr.MulScalar)
      val crossResult = PerlBridge.crossCheck(prog).unsafeRunSync()
      val scalaResult = ProgramEval.exec(prog)
      crossResult.shouldBe(scalaResult)

    it("Vec3 Dot: crossCheck == Scala 評価結果"):
      ensurePerl()
      val prog = Vector(
        Instr.PushVec3(1, 2, 3),
        Instr.PushVec3(4, 5, 6),
        Instr.DotVec3
      )
      val crossResult = PerlBridge.crossCheck(prog).unsafeRunSync()
      val scalaResult = ProgramEval.exec(prog)
      crossResult.shouldBe(scalaResult)

    it("BatchJob.dhall の frobenius-compile ステップ: 12 * 1 = 12"):
      ensurePerl()
      val prog = Vector(Instr.PushScalar(12), Instr.PushScalar(1), Instr.MulScalar)
      val crossResult = PerlBridge.crossCheck(prog).unsafeRunSync()
      crossResult.shouldBe(Right(ScalarValue(12)))

    it("BatchJob.dhall の padic-lower ステップ: (2,4,0)·(1,0,8) = 2"):
      ensurePerl()
      val prog = Vector(
        Instr.PushVec3(2, 4, 0),
        Instr.PushVec3(1, 0, 8),
        Instr.DotVec3
      )
      val crossResult = PerlBridge.crossCheck(prog).unsafeRunSync()
      crossResult.shouldBe(Right(ScalarValue(2 * 1 + 4 * 0 + 0 * 8)))