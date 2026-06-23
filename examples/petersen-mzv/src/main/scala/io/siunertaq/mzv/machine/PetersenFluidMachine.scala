package io.siunertaq.mzv.machine

import cats.effect.IO
import io.siunertaq.mzv.domain._

// ─── ??? Taxonomy ─────────────────────────────────────────────────────────
//
//  Two structurally distinct ??? sites exist in this machine.
//  Each maps to a distinct SMT property.
//
//  [TOPOLOGY ???]  — TopologyException
//    Site:    resolveStack / step, when getNeighbors finds no 2-hop path.
//    Nature:  Real-part ??? — topological guarantee derived from the graph structure.
//    Status:  DEAD CODE. P1 (UNSAT) formally proves diameter ≤ 2,
//             meaning this branch is statically unreachable at runtime.
//    Residue: Preserved as an explicit guard to reify the structural invariant.
//
//  [IMAGINARY ???]  — DivergentPoleException
//    Site:    applyPentagonRelation, when isConvergent = false (s1 = 1).
//    Nature:  Purely imaginary-part ??? — the odd-depth Fischer f9 obstruction.
//             "Popping as a dictionary yields the purely imaginary part":
//             when treating the stack as an indexed dictionary, popping the
//             divergent pole references the imaginary key—resident in the
//             index but possessing no finite evaluation.
//    Status:  LIVE. P5 (UNSAT) proves the contradiction of (s1=1 ∧ s1>1), but
//             s1=1 triples can still inhabit the input space (Test Case 2 in Main).
//             Dispatched asynchronously to ImaginaryPopperActor.
//
// ─────────────────────────────────────────────────────────────────────────

/** Raised by `resolveStack` when a 2-hop path does not exist.
  *
  * P1 (UNSAT) formally proves this state is structurally unreachable, representing
  * the real-part ??? invariant.
  */
final class TopologyException(from: Vertex, to: Vertex)
  extends RuntimeException(
    s"[TOPOLOGY ???] No 2-hop path from $from to $to. " +
    s"(P1/UNSAT guarantees Petersen diameter ≤ 2; this path should not exist.)")

/** Raised by `applyPentagonRelation` when encountering `s1 = 1`.
  *
  * P5 (UNSAT) formally documents this contradiction. Caught and processed
  * asynchronously by the `ImaginaryPopperActor`.
  */
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
      if current.equals(goal) then IO.pure(cur)
      else
        val nextOpt = getNeighbors(current)
          .find(v => v.equals(goal) || getNeighbors(v).exists(_.equals(goal)))
        nextOpt match
          case None =>
            IO.raiseError(TopologyException(current, goal))  // [TOPOLOGY ???] dead code
          case Some(next) =>
            applyPentagonRelation(cur, current, next)
              .flatMap(nextTriple => IO.defer(step(next, nextTriple)))

        step(start, triple)