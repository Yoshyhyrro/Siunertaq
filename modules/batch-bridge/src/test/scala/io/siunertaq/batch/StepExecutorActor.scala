package io.siunertaq.batch

import org.apache.pekko.actor.{Actor, ActorLogging, Props}
import org.springframework.batch.core.{ExitStatus, JobExecution, JobInstance, JobParameters, StepExecution}
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.transaction.PlatformTransactionManager

/** 単一ステップを担当するPekkoアクター。JCLの「ステップ実行スレッド」に相当。
 *
 *  ① CondEvaluator でスキップ判定
 *  ② スキップ → 親に StepCompleted(skipped=true) を送信
 *  ③ 実行   → StackMachineTasklet を Spring Batch Step として起動
 *  ④ ABEND  → StepAbended をthrow (Pekko Supervisor が OneForOneStrategy で Stop)
 *
 *  NOTE: step.execute() はブロッキング。
 *  本番では "batch-blocking-dispatcher" 等の専用スレッドプールを使用すること。
 */
class StepExecutorActor(
  jobRepository: JobRepository,
  txMgr:         PlatformTransactionManager
) extends Actor with ActorLogging:

  def receive: Receive =
    case RunStep(stepDef, maxPrevRC, abended) =>
      if CondEvaluator.shouldSkip(stepDef.cond, maxPrevRC, abended) then
        log.info("[COND SKIP] step={}", stepDef.name)
        context.parent ! Notify("INFO", "STEP FLUSHED BY COND", stepDef.name)
        context.parent ! StepCompleted(stepDef.name, rc = 0, skipped = true)
        context.stop(self)
      else
        try
          val rc = runSpringBatchStep(stepDef)
          log.info("[STEP DONE] step={} RC={}", stepDef.name, rc)
          context.parent ! Notify("INFO", s"STEP COMPLETE RC=$rc", stepDef.name)
          context.parent ! StepCompleted(stepDef.name, rc = rc)
          context.stop(self)
        catch
          case ex: Throwable =>
            // Pekko Supervisor に伝播 → OneForOneStrategy が Stop を適用
            // 親の Terminated ハンドラがABENDとして認識する
            throw StepAbended(stepDef.name, ex)

  private def runSpringBatchStep(stepDef: StepDef): Int =
    val tasklet = StackMachineTasklet(stepDef.inputProg, stepDef.name)
    val step = StepBuilder(stepDef.name, jobRepository)
      .tasklet(tasklet, txMgr)
      .build()

    // JobRepository にJobExecution/StepExecution を登録して実行
    val jobInst  = jobRepository.createJobInstance(
      "siunertaq", JobParameters()
    )
    val jobExec  = jobRepository.createJobExecution(
      jobInst, JobParameters(), "BatchJob.dhall"
    )
    val stepExec = jobExec.createStepExecution(stepDef.name)
    jobRepository.add(stepExec)

    step.execute(stepExec)
    CondEvaluator.exitStatusToRC(stepExec.getExitStatus.getExitCode)

object StepExecutorActor:
  def props(r: JobRepository, t: PlatformTransactionManager): Props =
    Props(StepExecutorActor(r, t))