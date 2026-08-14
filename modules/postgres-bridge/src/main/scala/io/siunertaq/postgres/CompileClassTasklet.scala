package io.siunertaq.postgres

import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.ActorRef
import org.springframework.batch.core.{ExitStatus, StepContribution}
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus

import java.nio.file.Paths

// ─── CompileClassTasklet ─────────────────────────────────────────────────────
//
//  Gives ClassASTBridge.compileFromClassFile a Spring Batch Tasklet, the
//  same role StackMachineTasklet (modules/batch-bridge) plays for the BSD
//  Quiver stack machine -- same shape (execute/StepContribution/
//  ChunkContext/RepeatStatus.FINISHED, results written to the step's
//  JobExecutionContext, ExitStatus.FAILED with addExitDescription on
//  error) so a step's outcome is visible the same way regardless of which
//  Tasklet ran it.
//
//  Unlike StackMachineTasklet, compileFromClassFile returns IO[Unit], not
//  an Either -- .attempt converts a raised UnsupportedBytecodeException /
//  OverloadedMethodException / MethodNotFoundException /
//  IncompleteTranslationException (or a plain file-read IOException) into
//  a value this Tasklet can report through ExitStatus.FAILED, rather than
//  letting it propagate as an uncaught exception out of execute().
//
//  Thread safety: IO execution is done via IORuntime.global by default,
//  same as StackMachineTasklet. step.execute() (Spring Batch) is blocking
//  either way.

final class CompileClassTasklet(
  target:         ClassCompilationTarget,
  clickHouseSync: ActorRef,
  ioRuntime:      IORuntime = IORuntime.global
) extends Tasklet:

  override def execute(
    contribution: StepContribution,
    chunkContext: ChunkContext
  ): RepeatStatus =

    val execCtx = chunkContext
      .getStepContext
      .getStepExecution
      .getJobExecution
      .getExecutionContext

    val stepName = target.classFilePath

    val outcome =
      ClassASTBridge
        .compileFromClassFile(
          Paths.get(target.classFilePath),
          clickHouseSync,
          target.targetMethod,
          target.targetDescriptor,
          target.allowStubs
        )
        .attempt
        .unsafeRunSync()(using ioRuntime)

    outcome match
      case Right(()) =>
        execCtx.put(s"$stepName.result", "COMPILED")
        contribution.setExitStatus(ExitStatus.COMPLETED)

      case Left(error) =>
        val msg = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
        execCtx.put(s"$stepName.error", msg)
        contribution.setExitStatus(
          ExitStatus.FAILED.addExitDescription(msg)
        )

    RepeatStatus.FINISHED