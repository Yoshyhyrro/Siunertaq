package io.siunertaq.yangbaxter

import scala.math.{ cos, sin }

// ─── §1  Braid group generators ──────────────────────────────────────────────

/** Artin braid group generator σ_i.
 *  Lean: `inductive BraidGen : Type where | σ : Fin 23 → BraidGen | σ_inv : Fin 23 → BraidGen`
 */
enum BraidGen derives CanEqual:
  /** Forward generator: σ_i (positive crossing). */
  case σ(i: Fin23)
  /** Inverse generator: σ_i⁻¹ (negative crossing). */
  case σInv(i: Fin23)

/** Index into the 23-strand braid group `B₂₃` (valid range: 0–22). */
opaque type Fin23 = Int

object Fin23:
  def apply(n: Int): Either[String, Fin23] =
    if n >= 0 && n <= 22 then Right(n)
    else Left(s"Fin23 index out of range: $n (expected 0–22)")

  def unsafeApply(n: Int): Fin23 = n  // for internal / spec-driven use

/** A word in the braid group B₂₃: a finite list of generators. */
type BraidWord = List[BraidGen]

// ─── §2  Yang-Baxter operator ────────────────────────────────────────────────

/** Dimension-`n` Yang-Baxter operator.
 *
 *  Lean:
 *  {{{
 *  structure YangBaxterOperator (n : ℕ) where
 *    R         : Matrix (Fin n × Fin n) (Fin n × Fin n) ℂ
 *    yang_baxter_eq : (R ⊗ 1) * (1 ⊗ R) * (R ⊗ 1) = (1 ⊗ R) * (R ⊗ 1) * (1 ⊗ R)
 *  }}}
 *
 *  In Scala we omit the proof field and keep the matrix as a raw function,
 *  pending a Cats / Spire matrix abstraction layer.
 */
trait YangBaxterOperator[N <: Int]:
  /** R-matrix entry R((i₁, i₂), (j₁, j₂)): ℂ represented as `(Double, Double)`. */
  def r(i1: Int, i2: Int, j1: Int, j2: Int): (Double, Double)

// ─── §3  24-element Pauli group ──────────────────────────────────────────────

/** Elements of the 24-element discrete Pauli group (Lean: `inductive Pauli24`). */
enum Pauli24 derives CanEqual:
  case I, X, Y, Z, nI, nX, nY, nZ

// ─── §8  Spiral rotation → Satake spectral parameter ─────────────────────────

/** Spiral rotation parameters encoding a Berkovich point on the p-adic disc.
 *
 *  Lean:
 *  {{{
 *  structure SpiralRotation where
 *    angle         : ℝ
 *    scalingFactor : ℂ
 *  }}}
 *
 *  The complex `scalingFactor` is split into `(scalingRe, scalingIm)` to avoid
 *  importing `io.siunertaq.carabiner.ComplexWeight` and creating a circular
 *  dependency (PhantomCarabiner → YangBaxterBanach → carabiner would be a cycle).
 *  Callers in the `carabiner` package wrap the result of `spiralToSpectralParam`
 *  into `ComplexWeight` themselves.
 *
 *  @param angle      rotation angle θ ∈ ℝ
 *  @param scalingRe  Re(λ), real part of the complex scaling factor λ
 *  @param scalingIm  Im(λ), imaginary part of λ (default `0.0` for purely real λ)
 */
final case class SpiralRotation(
  angle:      Double,
  scalingRe:  Double,
  scalingIm:  Double = 0.0
) derives CanEqual

/** Satake spectral parameter: u = λ · exp(iθ).
 *
 *  Lean: `def spiralToSpectralParam (spiral : SpiralRotation) : ℂ`
 *
 *  Returns `(re, im)` of the complex number `u`:
 *  {{{
 *  re(u) = scalingRe · cos θ − scalingIm · sin θ
 *  im(u) = scalingRe · sin θ + scalingIm · cos θ
 *  }}}
 *
 *  The Satake parametrisation: as `(angle, |λ|)` sweeps `ℝ × ℝ_{>0}`, the
 *  image `u` sweeps the maximal torus `T̂` of GL₂ modulo the Weyl group.
 */
def spiralToSpectralParam(s: SpiralRotation): (Double, Double) =
  val cosA = cos(s.angle)
  val sinA = sin(s.angle)
  (
    s.scalingRe * cosA - s.scalingIm * sinA,   // Re(λ exp(iθ))
    s.scalingRe * sinA + s.scalingIm * cosA    // Im(λ exp(iθ))
  )

/** Spectral R-matrix `R(u)` for spectral parameter `u = (re, im)`.
 *
 *  Lean: `def spectralRMatrix (u : ℂ) : YangBaxterOperator 2`
 *  {{{
 *  R(u)((i₁,i₂),(j₁,j₂)) = u · δᵢ₁ⱼ₁ δᵢ₂ⱼ₂ + δᵢ₁ⱼ₂ δᵢ₂ⱼ₁
 *  }}}
 *  (rational GL₂ R-matrix: `u · Id⊗Id + P`, where P is the swap).
 *
 *  NOTE: `yang_baxter_eq` is `sorry` in the Lean source.
 */
def spectralRMatrix(uRe: Double, uIm: Double): YangBaxterOperator[2] =
  new YangBaxterOperator[2]:
    def r(i1: Int, i2: Int, j1: Int, j2: Int): (Double, Double) =
      val d1: Double = if i1 == j1 then 1.0 else 0.0
      val d2: Double = if i2 == j2 then 1.0 else 0.0
      val s1: Double = if i1 == j2 then 1.0 else 0.0
      val s2: Double = if i2 == j1 then 1.0 else 0.0
      // u · d1·d2 + s1·s2   (all real: u acts on a real coefficient here)
      (uRe * d1 * d2 + s1 * s2, uIm * d1 * d2)

// ─── §9  Yang-Baxter trace (automorphic L-function hook) ─────────────────────
//
//  The full trace construction (Lean §9) computing
//    L(s, π) = ∑_{n ≥ 1} a_n · n^{-s}
//  from the spectral R-matrix is left as a TODO pending a Cats streams / FS2
//  integration layer.  See `YangBaxterBanach.lean` §9 for the Lean spec.
