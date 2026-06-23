package io.siunertaq.batch

import cats.effect.unsafe.IORuntime
import io.siunertaq.expr.{ProgramEval, Program}
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus

// ─── StackMachineTasklet ─────────────────────────────────────────────────────
//
//  BSDQuiver スタックマシンを Spring Batch Tasklet として包む。
//
//  v2 追加: PerlBridge GADT 相互検証
//  ─────────────────────────────────
//  RUN_PERL_CROSSCHECK=1 が設定されている場合:
//
//    stepDef.inputProg
//         │
//         ├─ ProgramEval.exec         (Scala JVM スタックマシン)
//         │        └── JobExecutionContext "$stepName.result"
//         │
//         └─ PerlBridge.maybeCheckIO  (Strawberry Perl サブプロセス)
//                  │
//                  ├── ProgramLifter.liftTyped → TypedResult  ← GADTと関数が一致
//                  │     ScalarTyped → TypedParser[Scalar] → parse(perlOutput)
//                  │     Vec3Typed   → TypedParser[Vec3]   → parse(perlOutput)
//                  │
//                  └── JobExecutionContext "$stepName.perl_check"
//                        "MATCH: ScalarValue(42)"       → 一致
//                        "MISMATCH: Scala=... Perl=..." → 不一致 (FAILED に設定)
//
//  Perl 非インストール / RUN_PERL_CROSSCHECK 未設定 の場合:
//    "$stepName.perl_check" = "SKIP: ..." と記録し、ExitStatus は変更しない。
//
//  スレッド安全性:
//    IO 実行は IORuntime.global (cats-effect) 経由で行う。
//    PerlBridge.executePerl は IO.blocking を使用し Spring Batch の
//    スレッドプールをブロックしない。

final class StackMachineTasklet(
  program:  Program,
  stepName: String,
  ioRuntime: IORuntime = IORuntime.global
) extends Tasklet:

  override def execute(
    contribution: StepContribution,
    chunkContext:  ChunkContext
  ): RepeatStatus =

    val execCtx = chunkContext
      .getStepContext
      .getStepExecution
      .getJobExecution
      .getExecutionContext

    // ── Step 1: Scala スタックマシン評価 (必須) ───────────────────────────
    ProgramEval.exec(program) match
      case Left(error) =>
        execCtx.put(s"$stepName.error", error)
        contribution.setExitStatus(
          org.springframework.batch.core.ExitStatus.FAILED
            .addExitDescription(error)
        )

      case Right(scalaValue) =>
        execCtx.put(s"$stepName.result", scalaValue.toString)
        contribution.setExitStatus(
          org.springframework.batch.core.ExitStatus.COMPLETED
        )

        // ── Step 2: Perl 相互検証 (オプション) ────────────────────────────
        //
        //  PerlBridge.maybeCheckIO が返す Either[String, Value]:
        //    Right(value) : Scala == Perl → "$stepName.perl_check" = "MATCH: $value"
        //    Left(skip)   : 検証スキップ  → "$stepName.perl_check" = "SKIP: ..."
        //    Left(mismatch): 不一致       → ExitStatus を FAILED に変更
        //
        //  GADT dispatch の仕組み (PerlBridge.runViaPerl より):
        //    liftTyped(program) = ScalarTyped(_)  →  TypedParser[Scalar] を使用
        //    liftTyped(program) = Vec3Typed(_)    →  TypedParser[Vec3]   を使用
        //  T を固定するのは TypedResult の match — 型安全な関数選択。

        val checkResult =
          PerlBridge.maybeCheckIO(program, stepName).unsafeRunSync()(ioRuntime)

        checkResult match
          case Right(perlValue) =>
            execCtx.put(s"$stepName.perl_check", s"MATCH: $perlValue")

          case Left(msg) if msg.startsWith("[PerlBridge] SKIP") =>
            execCtx.put(s"$stepName.perl_check", msg)

          case Left(mismatch) if mismatch.contains("MISMATCH") =>
            // Scala と Perl の結果が食い違う → バグの可能性 → FAILED
            execCtx.put(s"$stepName.perl_check", mismatch)
            contribution.setExitStatus(
              org.springframework.batch.core.ExitStatus.FAILED
                .addExitDescription(s"[PerlBridge] $mismatch")
            )

          case Left(perlErr) =>
            // Perl の実行自体が失敗 (perl が PATH にない等) → WARN 扱い
            execCtx.put(s"$stepName.perl_check", s"PERL_ERROR: $perlErr")

    RepeatStatus.FINISHED
