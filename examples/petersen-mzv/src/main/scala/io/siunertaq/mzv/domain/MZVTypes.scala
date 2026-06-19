package io.siunertaq.mzv.domain

import scala.beans.BeanProperty

// ─── Petersen Graph Vertices ──────────────────────────────────────────────
//
//   SMT encoding:  Outer(i) = i      (i ∈ 0..4, even-depth sector)
//                  Inner(i) = i + 5  (i ∈ 0..4, odd-depth sector)
//
//   Design note on ???:
//     Two distinct ??? sites exist in PetersenMachine:
//
//     [TOPOLOGY ???]  resolveStack: `IO.delay(???)`
//       Fires when the diameter-2 invariant is violated (no 2-hop path).
//       P1 (UNSAT) proves this arm is dead code.
//       → "実部" の ??? — eliminated structurally by the graph topology.
//
//     [IMAGINARY ???]  ImaginaryPopperActor: `Status.Failure(new NotImplementedError(...))`
//       Fires when s1 = 1 (divergent pole = Fischer f9 / odd-depth obstruction).
//       P5 (UNSAT) documents the contradiction: s1=1 ∧ s1>1 is always false.
//       → "純虚部" の ??? — popped asynchronously by the actor (辞書としての pop).
//
//   Lean4 対応:
//     Inner = HNWeight.d1 / d3 / d5  (isOddDepth = true, mzvParity = -1)
//     Outer = HNWeight.d0 / d2 / d4  (isOddDepth = false, mzvParity = +1)

sealed trait Vertex { def phase: Int }

/** Even-depth sector (d0/d2/d4). Clean duality with complement. */
final case class Outer(phase: Int) extends Vertex:
  require(phase >= 0 && phase < 5, s"Outer phase $phase ∉ ℤ/5ℤ")

/** Odd-depth sector (d1/d3/d5). Fischer f9 / IKZ obstruction sector. */
final case class Inner(phase: Int) extends Vertex:
  require(phase >= 0 && phase < 5, s"Inner phase $phase ∉ ℤ/5ℤ")

// ─── MZV Triple ───────────────────────────────────────────────────────────
//
//   ζ(s1, s2, s3) depth-3 multiple zeta value.
//   @BeanProperty: Spring IoC / Java interop.
//   mzvWeight = s1 + s2 + s3 (SMT-verified: s2+s3 is conserved, so mzvWeight is conserved).

final case class MZVTriple(
  @BeanProperty s1: Int,
  @BeanProperty s2: Int,
  @BeanProperty s3: Int
):
  /** s1 > 1: convergent (safe for IUT / Chen integral). s1 = 1: divergent pole → ImaginaryPopper. */
  def isConvergent: Boolean = s1 > 1

  /** Total weight. P2 (UNSAT) proves s2+s3 is invariant under applyPentagonRelation. */
  def mzvWeight: Int = s1 + s2 + s3
