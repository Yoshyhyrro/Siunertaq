package io.siunertaq.batch

import cats.effect.unsafe.IORuntime
import io.siunertaq.expr.{ProgramEval, Program}
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus

// ─── StackMachineTasklet ─────────────────────────────────────────────────────
//
//  BSDQuiver / Siunertaq Scala JVM is a stack machine interpreter for the DSL defined in modules/batch-bridge/src/main/resources/Siunertaq/StackMachine.dhall. 
//
//  v2 and later versions of the DSL are designed to be compatible with the Perl implementation in modules/batch-bridge/src/main/resources/perl/Siunertaq/StackMachine.pm.
//  ─────────────────────────────────
//  RUN_PERL_CROSSCHECK=1 the environment variable enables cross-checking the Scala stack machine's output against the Perl implementation.
//
//    stepDef.inputProg
//         │
//         ├─ ProgramEval.exec         (Scala JVM stack machine interpreter)
//         │        └── JobExecutionContext "$stepName.result"
//         │
//         └─ PerlBridge.maybeCheckIO  (Strawberry Perl subprocess execution)
//                  │
//                  ├── ProgramLifter.liftTyped → TypedResult  ← GADT is used to select the correct TypedParser for the lifted type T
//                  │     ScalarTyped → TypedParser[Scalar] → parse(perlOutput)
//                  │     Vec3Typed   → TypedParser[Vec3]   → parse(perlOutput)
//                  │
//                  └── JobExecutionContext "$stepName.perl_check"
//                        "MATCH: ScalarValue(42)"       → Scala and Perl outputs match
//                        "MISMATCH: Scala=... Perl=..." → Scala and Perl outputs do not match (potential bug) → ExitStatus is set to FAILED
//
//  Perl not installed / RUN_PERL_CROSSCHECK not set:
//    "$stepName.perl_check" = "SKIP: ..." is recorded, ExitStatus is not changed.
//
//  Thread safety:
//    IO execution is done via IORuntime.global (cats-effect).
//    PerlBridge.executePerl uses IO.blocking to avoid blocking Spring Batch's
//    thread pool.

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

    // ── Step 1: Scala Stack Machine Interpreter ───────────────────────────────
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

        // ── Step 2: Perl Cross-Check (Optional) ───────────────────────────────
        //
        //  PerlBridge.maybeCheckIO returns Either[String, Value]:
        //    Right(value) : Scala == Perl → "$stepName.perl_check" = "MATCH: $value"
        //    Left(skip)   : Check skipped  → "$stepName.perl_check" = "SKIP: ..."
        //    Left(mismatch): Mismatch       → ExitStatus is set to FAILED
        //
        //  GADT dispatch is used to select the correct TypedParser for the lifted type T:
        //    liftTyped(program) = ScalarTyped(_)  →  TypedParser[Scalar] is used
        //    liftTyped(program) = Vec3Typed(_)    →  TypedParser[Vec3]   is used
        //  Fixing T is done via pattern matching on TypedResult — type-safe function selection.

        val checkResult =
          PerlBridge.maybeCheckIO(program, stepName).unsafeRunSync()(using ioRuntime)

        checkResult match
          case Right(perlValue) =>
            execCtx.put(s"$stepName.perl_check", s"MATCH: $perlValue")

          case Left(msg) if msg.startsWith("[PerlBridge] SKIP") =>
            execCtx.put(s"$stepName.perl_check", msg)

          case Left(mismatch) if mismatch.contains("MISMATCH") =>
            // Scala and Perl results mismatch → Possible bug → FAILED
            execCtx.put(s"$stepName.perl_check", mismatch)
            contribution.setExitStatus(
              org.springframework.batch.core.ExitStatus.FAILED
                .addExitDescription(s"[PerlBridge] $mismatch")
            )

          case Left(perlErr) =>
            // Perl execution itself failed (e.g., perl not in PATH) → WARN
            execCtx.put(s"$stepName.perl_check", s"PERL_ERROR: $perlErr")

    RepeatStatus.FINISHED