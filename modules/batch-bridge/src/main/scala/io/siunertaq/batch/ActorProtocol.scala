package io.siunertaq.batch

import org.apache.pekko.actor.ActorRef

// ─── JCL と対応するメッセージプロトコル ─────────────────────────────────

/** JCL JOBカード相当: ジョブ全体の実行を依頼 */
final case class RunJob(jobDef: BatchJobDef)

/** JCL EXECカード相当: 単一ステップの実行を依頼 */
final case class RunStep(
  stepDef:   StepDef,
  maxPrevRC: Int,       // 前ステップ群の最大RC (COND評価に使用)
  abended:   Boolean    // 前ステップ群でABENDが発生したか
)

/** ステップ正常完了 (スキップを含む) */
final case class StepCompleted(
  stepName: String,
  rc:       Int,
  skipped:  Boolean = false
)

/** NOTE / WTO相当: JCLの通知機能 */
final case class Notify(level: String, message: String, stepName: String)

/** ジョブ完了 (全ステップ終了後にreplyToへ送信) */
final case class JobResult(jobName: String, notes: List[String], maxRC: Int)

/** ABEND: 子アクターがこれをthrowするとSupervisorStrategyが捕捉する */
final class StepAbended(val stepName: String, cause: Throwable)
  extends RuntimeException(s"ABEND in step '$stepName'", cause)