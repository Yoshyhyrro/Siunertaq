package io.siunertaq.batch

import org.apache.pekko.actor.{
  Actor, ActorLogging, ActorRef, OneForOneStrategy,
  Props, SupervisorStrategy, Terminated
}
import org.apache.pekko.actor.SupervisorStrategy.Stop
import org.springframework.batch.core.repository.JobRepository
import org.springframework.transaction.PlatformTransactionManager
import scala.concurrent.duration._

/** JES2 / MVS Job Supervisor Actor
 *
 *  ・OneForOneStrategy: child actor (StepExecutorActor) is stopped (Stop) on StepAbended (ABEND), restarted (Restart) on other exceptions.
 *    This is equivalent to JCL ABEND, which stops only the child actor that ab
 *    the ABEND, and continues with the remaining steps. Other exceptions are retried once.
 *    
 *
 *  ・Terminated handler: child actor (StepExecutorActor) is stopped without sending StepCompleted → ABEND (StepAbended) is detected.
 *
 *  ・Notify: JCL WTO / NOTE equivalent. StepExecutorActor sends Notify to JobSupervisorActor, which logs it and forwards it to the parent actor (SiunertaqBatchApp).
 */
class JobSupervisorActor(
  jobRepository: JobRepository,
  txMgr:         PlatformTransactionManager
) extends Actor with ActorLogging:

  // ABEND: child actor (StepExecutorActor) throws StepAbended, which is caught by Pekko Supervisor and treated as ABEND. The parent actor (JobSupervisorActor) will receive Terminated message and recognize it as ABEND.
  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 1.minute):
      case _: StepAbended => Stop
      case _: Exception   => SupervisorStrategy.Restart

  // ─── job state ───────────────────────────────────────────────────────────────
  private var jobName:        String        = ""
  private var remainingSteps: List[StepDef] = Nil
  private var maxRC:          Int           = 0
  private var abended:        Boolean       = false
  private var replyTo:        Option[ActorRef] = None
  private val notes:          scala.collection.mutable.ArrayBuffer[String] = 
    scala.collection.mutable.ArrayBuffer.empty
  private var pendingStepName: Option[String] = None   // Terminated detection

  def receive: Receive =

    case RunJob(jobDef) =>
      log.info("[JOB START] job={} prime={}", jobDef.jobName, jobDef.prime)
      jobName = jobDef.jobName
      replyTo = Some(sender())
      remainingSteps = jobDef.steps.sortBy(_.priority)
      dispatchNextStep()

    case StepCompleted(name, rc, skipped) =>
      pendingStepName = None
      val action = if skipped then "SKIPPED" else s"RC=$rc"
      log.info("[STEP RESULT] step={} {}", name, action)
      maxRC = math.max(maxRC, rc)
      dispatchNextStep()

    case Notify(level, message, stepName) =>
      val entry = s"[$level] $stepName: $message"
      log.info("{}", entry)
      notes += entry

    case Terminated(ref) =>
      // StepCompleted not sent → ABEND detected (equivalent to JCL ABEND)
      pendingStepName.foreach { name =>
        log.error("[ABEND] step={} actor stopped without completion", name)
        notes += s"[ABEND] $name: actor terminated without StepCompleted"
        abended = true
        pendingStepName = None
        dispatchNextStep()
      }

  private def dispatchNextStep(): Unit =
    remainingSteps match
      case Nil =>
        log.info("[JOB END] maxRC={} abended={}", maxRC, abended)
        replyTo.foreach(_ ! JobResult(jobName, notes.toList, maxRC))
      case stepDef :: rest =>
        remainingSteps = rest
        val actor = context.actorOf(
          StepExecutorActor.props(jobRepository, txMgr),
          name = s"step-${stepDef.name}"
        )
        context.watch(actor)   // Terminated is sent if child actor stops without sending StepCompleted → ABEND detection
        pendingStepName = Some(stepDef.name)
        actor ! RunStep(stepDef, maxRC, abended)

object JobSupervisorActor:
  def props(r: JobRepository, t: PlatformTransactionManager): Props =
    Props(JobSupervisorActor(r, t))