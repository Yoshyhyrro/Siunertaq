package io.siunertaq.postgres

// ─── ForthOp — ForthRegistrarActor メッセージプロトコル ─────────────────────
//
//  ActorProtocol.scala (batch-bridge) の RunStep / StepCompleted と
//  対称な構造。RegisterStep → Done の往復が1ターン。

object ForthOp:

  final case class RegisterStepArgs(
    jobName:      String,
    stepName:     String,
    effectTag:    String,
    targetVertex: String,   // BSDVertex name: "Leech", "AffineDual", etc.
    priority:     Int,
    instructions: io.circe.Json
  )

  final case class RegisterPopArgs(
    origS1: Int, origS2: Int, origS3: Int,
    regS1:  Int, regS2:  Int, regS3:  Int,
    src: String, srcPhase: Int,
    tgt: String, tgtPhase: Int
  )

  final case class RegisterStep(args: RegisterStepArgs)
  final case class RegisterPop(args: RegisterPopArgs)
  final case class Done(compiledStep: String)
  final case class ForthFailed(op: String, cause: Throwable)
