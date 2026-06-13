package io.siunertaq.mzv.enterprise

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

import io.siunertaq.mzv.domain._
import io.siunertaq.mzv.machine.{DivergentPoleException, PetersenFluidMachine}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

// ─────────────────────────────────────────────────────────────────────────
//  MZVMachineBean
//
//  Spring @Service that exposes PetersenFluidMachine to the IoC container.
//
//  Responsibility split:
//    resolveIO   → pure IO pipeline (cats-effect); callers decide runtime
//    resolve     → blocking convenience method (for legacy Spring consumers)
//    resolveSafe → intercepts DivergentPoleException and routes to
//                  ImaginaryPopperActor, returning a Future[MZVTriple]
//
//  ActorSystem and IORuntime are injected by Spring so the bean itself
//  stays deterministic and testable.
// ─────────────────────────────────────────────────────────────────────────

@Service
class MZVMachineBean @Autowired() (
  actorSystem: ActorSystem,
  ioRuntime:   IORuntime
):
  private val machine     = new PetersenFluidMachine()
  private val imaginaryPopper: ActorRef =
    actorSystem.actorOf(ImaginaryPopperActor.props, "imaginaryPopper")

  implicit private val timeout: Timeout = Timeout(5.seconds)
  implicit private val ec: ExecutionContext = actorSystem.dispatcher

  // ── Pure IO pipeline ────────────────────────────────────────────────────

  /** Full traversal as IO.  Caller decides how to run it. */
  def resolveIO(triple: MZVTriple, start: Vertex, goal: Vertex): IO[MZVTriple] =
    machine.resolveStack(triple, start, goal)

  // ── Blocking convenience (legacy Spring consumers) ──────────────────────

  /** Blocking resolve for Spring @Controller / @RestController endpoints.
    * Throws on both ??? sites — caller must handle. */
  def resolve(triple: MZVTriple, start: Vertex, goal: Vertex): MZVTriple =
    resolveIO(triple, start, goal).unsafeRunSync()(ioRuntime)

  // ── Async resilient path (routes imaginary ??? to actor) ─────────────────

  /**
   * Resolve with automatic rerouting of [IMAGINARY ???].
   *
   * - Convergent triples (s1 > 1) → IO pipeline → Future.successful
   * - Divergent triples (s1 = 1) → ImaginaryPopperActor → Future[MZVTriple]
   *   (popped and regularized, then retried through the machine)
   *
   * [TOPOLOGY ???] (TopologyException) still propagates as a Future failure;
   * it is structurally unreachable (P1/UNSAT) but must not be silently swallowed.
   */
  def resolveSafe(triple: MZVTriple, start: Vertex, goal: Vertex): Future[MZVTriple] =
    resolveIO(triple, start, goal).unsafeToFuture()(ioRuntime).recoverWith {
      case ex: DivergentPoleException =>
        val msg = ImaginaryPopperActor.fromException(ex, start, goal,
          actorSystem.deadLetters /* replyTo: overridden below */)
        (imaginaryPopper ? ImaginaryPopperActor.PopImaginary(
          ex.triple, start, goal, actorSystem.deadLetters))
          .mapTo[ImaginaryPopperActor.ImaginaryPopped]
          .flatMap { popped =>
            // Retry machine with the regularized triple
            resolveIO(popped.regularized, start, goal).unsafeToFuture()(ioRuntime)
          }
    }

  // ── @BeanProperty-style accessors (Java / Spring EL interop) ────────────

  def getS1(triple: MZVTriple): Int = triple.getS1
  def getS2(triple: MZVTriple): Int = triple.getS2
  def getS3(triple: MZVTriple): Int = triple.getS3
