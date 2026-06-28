package io.siunertaq.batch

import org.apache.pekko.actor.ActorRef

// ─── JCL from Siunertaq Batch DSL ───────────────────────────────────────────────

/** that is jcl whith jobcard and exec card, but not jcl syntax */

/** JCL EXEC card equivalent: RunJob is sent to JobSupervisorActor */
final case class RunStep(
  stepDef:   StepDef,
  maxPrevRC: Int,       // this is the maximum RC of all previous steps (including skipped steps)
  abended:   Boolean    // true if any previous step has abended (failed with exception)
)

/** step completed (normal or skipped) is sent to JobSupervisorActor */
final case class StepCompleted(
  stepName: String,
  rc:       Int,
  skipped:  Boolean = false
)

/** NOTE / WTO anolosis: Notify is sent to JobSupervisorActor, which will log it and forward it to the parent actor (SiunertaqBatchApp) */
final case class Notify(level: String, message: String, stepName: String)

/** job completed (normal or abended) is sent to SiunertaqBatchApp */
final case class JobResult(jobName: String, notes: List[String], maxRC: Int)

/** ABEND: child actor (StepExecutorActor) throws StepAbended, which is caught by Pekko Supervisor and treated as ABEND. The parent actor (JobSupervisorActor) will receive Terminated message and recognize it as ABEND. */
final class StepAbended(val stepName: String, cause: Throwable)
  extends RuntimeException(s"ABEND in step '$stepName'", cause)