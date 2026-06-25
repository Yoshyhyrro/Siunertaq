package io.siunertaq.batch

import org.apache.pekko.actor.{
  Actor, ActorLogging, ActorRef, OneForOneStrategy,
  Props, SupervisorStrategy, Terminated
}
import org.apache.pekko.actor.SupervisorStrategy.Stop
import org.springframework.batch.core.repository.JobRepository
import org.springframework.transaction.PlatformTransactionManager
import scala.concurrent.duration._

/** JES2 / MVS スーパーバイザー相当のPekkoアクター。
 *
 *  ・OneForOneStrategy: 子ステップのABENDをシステム全体から隔離し
 *    その子アクターのみを安全停止 (Stop) させる。
 *    残りのステップは影響なく継続または COND=ONLY でリカバリ実行される。
 *
 *  ・Terminated ハンドラ: StepCompleted を送らずに停止した子 = ABEND と判定。
 *
 *  ・Notify: JCLの WTO (Write To Operator) / MSGCLASS Note に相当するログ収集。
 */
class JobSupervisorActor(
  jobRepository: JobRepository,
  txMgr:         PlatformTransactionManager
) extends Actor with ActorLogging:

  // ABEND: 子を再起動せず安全停止 (JCL ABEND相当)。他の例外は1回リトライ。
  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 1.minute):
      case _: StepAbended => Stop
      case _: Exception   => SupervisorStrategy.Restart

  // ─── ジョブ実行状態 ─────────────────────────────────────────────────
  private var remainingSteps: List[StepDef] = Nil
  private var maxRC:          Int           = 0
  private var abended:        Boolean       = false
  private var replyTo:        Option[ActorRef] = None
  private val notes:          scala.collection.mutable.ArrayBuffer[String] = 
    scala.collection.mutable.ArrayBuffer.empty
  private var pendingStepName: Option[String] = None   // Terminated検出用

  def receive: Receive =

    case RunJob(jobDef) =>
      log.info("[JOB START] job={} prime={}", jobDef.jobName, jobDef.prime)
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
      // StepCompletedを送らずに停止 → ABEND と判定 (JCL ABEND相当)
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
        replyTo.foreach(_ ! JobResult("SiunertaqBatch", notes.toList, maxRC))
      case stepDef :: rest =>
        remainingSteps = rest
        val actor = context.actorOf(
          StepExecutorActor.props(jobRepository, txMgr),
          name = s"step-${stepDef.name}"
        )
        context.watch(actor)   // Terminated で ABEND を検出
        pendingStepName = Some(stepDef.name)
        actor ! RunStep(stepDef, maxRC, abended)

object JobSupervisorActor:
  def props(r: JobRepository, t: PlatformTransactionManager): Props =
    Props(JobSupervisorActor(r, t))