package io.siunertaq.mzv.enterprise

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}
import io.siunertaq.mzv.domain._
import io.siunertaq.mzv.machine.DivergentPoleException

// ─────────────────────────────────────────────────────────────────────────
//  ImaginaryPopperActor
//
//  Handles the [IMAGINARY ???] boundary: DivergentPoleException (s1 = 1).
//
//  「辞書としてpopするなら純虚部」—
//  When the stack machine is read as a dictionary, the s1=1 pole is the
//  "imaginary key": it exists in the index (the triple is well-formed)
//  but has no finite convergent value.  This actor pops it asynchronously
//  by applying a minimal regularization lift (s1 := 2) and forwarding
//  the result to the original requester.
//
//  In a complete IUT / Furusho implementation this would apply the
//  full Pentagon-Hexagon regularization scheme; here we stub it with
//  the minimal convergent lift to keep the pipeline moving.
// ─────────────────────────────────────────────────────────────────────────

object ImaginaryPopperActor:

  // ── Protocol ────────────────────────────────────────────────────────────

  /** Pop the imaginary key: a divergent triple that arrived at s1 = 1. */
  final case class PopImaginary(
    triple:    MZVTriple,
    src:       Vertex,
    tgt:       Vertex,
    replyTo:   ActorRef
  )

  /** Regularized result: the pole has been lifted to a convergent value. */
  final case class ImaginaryPopped(
    original:    MZVTriple,
    regularized: MZVTriple
  )

  /** Convenience constructor for the supervisor. */
  def props: Props = Props(classOf[ImaginaryPopperActor])

  /** Build a PopImaginary from a DivergentPoleException. */
  def fromException(ex: DivergentPoleException, src: Vertex, tgt: Vertex, replyTo: ActorRef): PopImaginary =
    PopImaginary(ex.triple, src, tgt, replyTo)

// ── Actor ──────────────────────────────────────────────────────────────────

final class ImaginaryPopperActor extends Actor with ActorLogging:

  import ImaginaryPopperActor._

  override def receive: Receive =

    case PopImaginary(triple, src, tgt, replyTo) =>
      log.warning(
        "[IMAGINARY ???] Divergent pole received: {} on edge {}→{}. " +
        "s1={}, applying regularization lift.",
        triple, src, tgt, triple.s1)

      // Minimal convergent lift: s1 := 2 (the smallest value satisfying isConvergent).
      // mzvWeight is preserved: we compensate s2 so that s1+s2+s3 stays constant.
      //   original:     s1=1, s2, s3   → weight = 1+s2+s3
      //   regularized:  s1=2, s2-1, s3 → weight = 2+(s2-1)+s3 = 1+s2+s3  ✓
      val regularized = triple.copy(s1 = 2, s2 = triple.s2 - 1)

      log.info(
        "Imaginary pop complete: {} → {} (weight {} preserved)",
        triple, regularized, triple.mzvWeight)

      replyTo ! ImaginaryPopped(triple, regularized)
