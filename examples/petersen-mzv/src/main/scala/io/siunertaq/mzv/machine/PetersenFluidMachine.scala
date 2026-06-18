package io.siunertaq.mzv.machine

import cats.effect.IO
import io.siunertaq.mzv.domain._

// ─── ??? Taxonomy ─────────────────────────────────────────────────────────
//
//  Two structurally distinct ??? sites exist in this machine.
//  Each maps to a different SMT property.
//
//  [TOPOLOGY ???]  — TopologyException
//    Site:    resolveStack / step, when getNeighbors finds no 2-hop path.
//    Nature:  実部の ??? — topological guarantee from the graph structure.
//    Status:  DEAD CODE.  P1 (UNSAT) formally proves diameter ≤ 2,
//             so this arm can never be reached at runtime.
//    Residue: kept as an explicit guard to make the invariant visible.
//
//  [IMAGINARY ???]  — DivergentPoleException
//    Site:    applyPentagonRelation, when isConvergent = false (s1 = 1).
//    Nature:  純虚部の ??? — the odd-depth Fischer f9 obstruction.
//             「辞書としてpopするなら純虚部」: when the stack is treated as
//             a dictionary, popping the divergent pole is the imaginary key —
//             it exists in the index but has no finite value.
//    Status:  LIVE.  P5 (UNSAT) proves s1=1 ∧ s1>1 is impossible, but
//             s1=1 triples *can* enter the machine (Test Case 2 in Main).
//             Dispatched asynchronously to ImaginaryPopperActor.
//
// ─────────────────────────────────────────────────────────────────────────

/** Thrown by resolveStack when no 2-hop path exists.
  * P1 (UNSAT) proves this is structurally unreachable: real-part ???. */
final class TopologyException(from: Vertex, to: Vertex)
  extends RuntimeException(
    s"[TOPOLOGY ???] No 2-hop path from $from to $to. " +
    s"(P1/UNSAT guarantees Petersen diameter ≤ 2; this path should not exist.)")

/** Thrown by applyPentagonRelation when s1 = 1.
  * P5 (UNSAT) documents the contradiction. Caught by ImaginaryPopperActor. */
final class DivergentPoleException(val triple: MZVTriple)
  extends RuntimeException(
    s"[IMAGINARY ???] Divergent pole: s1=${triple.s1}=1. " +
    s"Dispatch to ImaginaryPopperActor for imaginary-part pop.")

// ─── Machine ──────────────────────────────────────────────────────────────

final class PetersenFluidMachine:

  /** 3-regular neighborhood in the Petersen graph. */
  def getNeighbors(vertex: Vertex): Set[Vertex] = vertex match
    case Outer(i) => Set(Outer((i + 1) % 5), Outer((i + 4) % 5), Inner(i))
    case Inner(i) => Set(Inner((i + 2) % 5), Inner((i + 3) % 5), Outer(i))

  /**
   * Apply one pentagon-relation step along edge src → tgt.
   *
   * Convergent triple (s1 > 1):  MZVTriple(s1, s2 + delta, s3 - delta)
   *   delta = 1  if Outer ↔ Inner class jump
   *   delta = -1 if same class (both Outer or both Inner)
   *
   * Divergent triple (s1 = 1): raises [IMAGINARY ???].
   *   s2 + s3 is conserved in both branches (P2 / UNSAT).
   */
  def applyPentagonRelation(triple: MZVTriple, src: Vertex, tgt: Vertex): IO[MZVTriple] =
    if !triple.isConvergent then
      IO.raiseError(DivergentPoleException(triple))   // [IMAGINARY ???]
    else
      val delta = if src.getClass.getName != tgt.getClass.getName then 1 else -1
      IO.pure(MZVTriple(triple.s1, triple.s2 + delta, triple.s3 - delta))

  /**
   * Traverse the Petersen graph from start to goal, applying pentagon
   * relations along the way.
   *
   * Diameter ≤ 2 (P1 / UNSAT) bounds the traversal to at most 2 hops.
   * IO.defer ensures stack-safety across recursive calls.
   */
  def resolveStack(triple: MZVTriple, start: Vertex, goal: Vertex): IO[MZVTriple] =

    def step(current: Vertex, cur: MZVTriple): IO[MZVTriple] =
      if current == goal then IO.pure(cur)
      else
        val nextOpt = getNeighbors(current)
          .find(v => v == goal || getNeighbors(v).contains(goal))
        nextOpt match
          case None =>
            IO.raiseError(TopologyException(current, goal))  // [TOPOLOGY ???] dead code
          case Some(next) =>
            applyPentagonRelation(cur, current, next)
              .flatMap(nextTriple => IO.defer(step(next, nextTriple)))

    step(start, triple)
