package io.siunertaq.batch

import org.apache.pekko.actor.{Actor, ActorLogging, Props}
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.transaction.PlatformTransactionManager

/** single step executor actor for a single step execution
 *
 *  ① CondEvaluator → CondEvaluator.shouldSkip(cond, maxPrevRC, abended)
 *  ② skip   → StepCompleted(stepDef.name, rc=0, skipped = true)
 *  ③ execute → StackMachineTasklet as Spring Batch Step
 *  ④ ABEND  → StepAbended thrown (Pekko Supervisor applies OneForOneStrategy Stop)
 *
 *  NOTE: step.execute() is blocking.
 *  In production, use a dedicated thread pool like "batch-blocking-dispatcher".
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
            // Pekko Supervisor will treat this as ABEND and stop the actor
            // Parent's Terminated handler will recognize this as ABEND
            throw StepAbended(stepDef.name, ex)

  private def runSpringBatchStep(stepDef: StepDef): Int =
    val tasklet = StackMachineTasklet(stepDef.inputProg, stepDef.name)
    val step = StepBuilder(stepDef.name, jobRepository)
      .tasklet(tasklet, txMgr)
      .build()

    // Spring Batch 5 with Pekko Actor: JobExecutionContext is not available in the constructor of Tasklet.
    //   createJobExecution(jobName: String, jobParameters: JobParameters): JobExecution
    //   JobInstance is created if not exists, JobExecution is created and returned.
    //   ver3.0.0: JobExecutionContext is available in the constructor of Tasklet.
    val jobExec  = jobRepository.createJobExecution("siunertaq", JobParameters())
    val stepExec = jobExec.createStepExecution(stepDef.name)
    jobRepository.add(stepExec)

    step.execute(stepExec)
    CondEvaluator.exitStatusToRC(stepExec.getExitStatus.getExitCode)

object StepExecutorActor:
  def props(r: JobRepository, t: PlatformTransactionManager): Props =
    Props(StepExecutorActor(r, t))