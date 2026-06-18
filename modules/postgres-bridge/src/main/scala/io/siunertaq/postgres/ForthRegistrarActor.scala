package io.siunertaq.postgres

import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.{Actor, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy}
import scala.concurrent.duration.*

// ─── ForthRegistrarActor — Pekko 薄ラッパー ──────────────────────────────────
//
//  cats Ref (ForthConnMachine) が「何を追跡するか」を担う。
//  このアクターが「いつ再接続するか」を担う。
//
//  JobSupervisorActor の子として配置:
//    JobSupervisorActor
//      ├── StepExecutorActor   StepAbended    → Stop    (既存)
//      └── ForthRegistrarActor SQLException   → Restart (新規)
//
//  本番: dispatcher = "postgres-blocking-dispatcher"
//    (blocking JDBC 呼び出しをデフォルトスレッドプールから分離)

class ForthRegistrarActor(
  machine:   ForthConnMachine,
  ioRuntime: IORuntime
) extends Actor with ActorLogging:

  // ── ライフサイクル ────────────────────────────────────────────────────────
  override def preStart(): Unit =
    machine.connect.unsafeRunSync()(ioRuntime)
    log.info("[FORTH] connected. phase={}", machine.phase.unsafeRunSync()(ioRuntime))

  override def postRestart(cause: Throwable): Unit =
    log.warning("[FORTH] reconnecting after: {}", cause.getMessage)
    machine.connect.unsafeRunSync()(ioRuntime)

  override def postStop(): Unit =
    machine.close.unsafeRunSync()(ioRuntime)
    log.info("[FORTH] connection closed.")

  // ── Supervision (StepExecutorActor と対称な構造) ──────────────────────────
  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute):
      case _: java.sql.SQLException => SupervisorStrategy.Restart
      case _: java.io.IOException   => SupervisorStrategy.Restart
      case _                        => SupervisorStrategy.Escalate

  // ── メッセージ処理 ────────────────────────────────────────────────────────
  def receive: Receive =
    case ForthOp.RegisterStep(args) =>
      val result = machine.withConn("register_step") { conn =>
        ForthRegistrar(conn).registerStep(args)
      }.unsafeRunSync()(ioRuntime)
      sender() ! ForthOp.Done(result)

    case ForthOp.RegisterPop(args) =>
      val result = machine.withConn("imaginary_pop") { conn =>
        ForthRegistrar(conn).registerImaginaryPop(args)
      }.unsafeRunSync()(ioRuntime)
      sender() ! ForthOp.Done(result)

object ForthRegistrarActor:
  def props(machine: ForthConnMachine, rt: IORuntime): Props =
    Props(ForthRegistrarActor(machine, rt))
    // 本番: .withDispatcher("postgres-blocking-dispatcher")
