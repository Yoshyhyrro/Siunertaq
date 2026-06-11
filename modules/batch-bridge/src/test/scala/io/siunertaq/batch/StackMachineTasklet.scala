package io.siunertaq.batch

import io.siunertaq.expr.{ProgramEval, Program}
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus

/** BSDQuiver スタックマシンを Spring Batch Tasklet として包む。
 *
 *  ProgramEval.exec(inputProg) の結果を JobExecutionContext に格納し
 *  成否を ExitStatus に変換する。これが「スタックマシンを入力器として使う」
 *  接点: Dhallで記述された input_prog がここで評価される。
 */
final class StackMachineTasklet(
  program:  Program,
  stepName: String
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

    ProgramEval.exec(program) match
      case Right(value) =>
        execCtx.put(s"$stepName.result", value.toString)
        contribution.setExitStatus(
          org.springframework.batch.core.ExitStatus.COMPLETED
        )
      case Left(error) =>
        execCtx.put(s"$stepName.error", error)
        contribution.setExitStatus(
          org.springframework.batch.core.ExitStatus.FAILED
            .addExitDescription(error)
        )

    RepeatStatus.FINISHED