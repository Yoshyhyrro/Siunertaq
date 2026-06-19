package io.siunertaq.carabiner

import cats.effect.{ IO, Ref }
import io.siunertaq.GolayWeight                                  // canonical; defined in BSDQuiver.scala
import io.siunertaq.yangbaxter.{ SpiralRotation, spiralToSpectralParam }
import scala.math.{ atan2, pow }
// Note: GolayWeight extension methods (berkovichHeight, orbitSize, complement)
// are defined in Carabiner.scala (same package); no extra import needed.

// ─── §0  ComplexWeight ───────────────────────────────────────────────────────

/** Opaque complex number for Berkovich-tree evaluation points.
 *
 *  - `re`: real Berkovich height (continuous analogue of `GolayWeight.height`)
 *  - `im`: obstruction residual degree (deviation from the real axis)
 *
 *  Kept opaque so callers cannot construct malformed weights or bypass the
 *  arithmetic primitives. Backed by `(Double, Double)` — zero runtime overhead.
 */
opaque type ComplexWeight = (Double, Double)

object ComplexWeight:

  /** Primary constructor. */
  def apply(re: Double, im: Double): ComplexWeight = (re, im)

  /** Lift a pure-real Berkovich height (im = 0). */
  def fromReal(h: Double): ComplexWeight = (h, 0.0)

  /** Lift a discrete `GolayWeight` to a phantom evaluation point.
   *  Uses `berkovichHeight` (∈ [0, 8]) not the raw integer weight value. */
  def fromGolayWeight(gw: GolayWeight): ComplexWeight = fromReal(gw.berkovichHeight)

  val zero: ComplexWeight = (0.0, 0.0)

  extension (w: ComplexWeight)
    def re: Double = w._1
    def im: Double = w._2

    /** Complex conjugate: `re − im·i`. */
    def conj: ComplexWeight = (w.re, -w.im)

    /** Squared modulus `|w|² = re² + im²`. */
    def normSq: Double = w.re * w.re + w.im * w.im

    /** Argument (principal value in `(−π, π]`): Berry phase angle. */
    def arg: Double = atan2(w.im, w.re)

    def +(other: ComplexWeight): ComplexWeight = (w.re + other.re, w.im + other.im)
    def -(other: ComplexWeight): ComplexWeight = (w.re - other.re, w.im - other.im)

    /** Real scalar multiplication (SMul ℝ counterpart). */
    def *(r: Double): ComplexWeight = (w.re * r, w.im * r)
    def /(r: Double): ComplexWeight = (w.re / r, w.im / r)

    /** Multiply by `−i` (clockwise quarter-turn, −π/2 rotation).
     *
     *  `−w·i = im + (−re)·i`.
     *  Proof: `−(a + bi)·i = (−a − bi)·i = −ai + b = b − ai`.
     *
     *  Used by `thetaLink`; satisfies:
     *  - `re(mulNegI) = im(w)`   (theta_link_re)
     *  - `im(mulNegI) = −re(w)`  (theta_link_im)
     */
    def mulNegI: ComplexWeight = (w.im, -w.re)

// ─── §1  PhantomCarabiner structure ─────────────────────────────────────────

/** Complex-weight lift of the discrete `Carabiner`.
 *
 *  A *phantom carabiner* places an evaluation point on the Berkovich tree at a
 *  genuinely complex position `weight ∈ ℂ`.  The name alludes to the phantom
 *  component of a Witt vector: the imaginary part lives "in the background" and
 *  becomes visible only after applying `verschiebung` or `thetaLink`.
 *
 *  Faithful Scala translation of `HatsuYakitori.PhantomCarabiner.ComplexCarabiner`
 *  (see `PhantomCarabiner.lean`).
 *
 *  @param weight Complex Berkovich evaluation point; `re` = height, `im` = residual.
 *  @param phase  Order of the discrete phase group.  Default `4` gives the `ℤ/4ℤ`
 *                Pauli phase of `Carabiner`; `24` records a full Mathieu-group orbit.
 */
final case class PhantomCarabiner(
  weight: ComplexWeight,
  phase:  Int = 4
) derives CanEqual:

  // ── §2  Core operations ──────────────────────────────────────────────────

  /** Complement: reflect across the critical midpoint `3 + 0·i`.
   *
   *  `w ↦ 6 − conj(w)` combines conjugation (which negates `im`) with a
   *  reflection `z ↦ 6 − z` (which negates it back), so:
   *  - `complement.weight.re = 6 − weight.re`  (complement_re)
   *  - `complement.weight.im = weight.im`       (complement_im)
   *
   *  On the real axis this gives `h ↦ 6 − h`, the self-duality relation for
   *  the critical strip with half-bound `K = 6` (vs. `K = 8` in `Carabiner`).
   *  The complement is an involution: `complement.complement = id`
   *  (complement_involutive).
   */
  def complement: PhantomCarabiner =
    copy(weight = ComplexWeight(6.0 - weight.re, weight.im))

  /** Verschiebung: halve the complex weight.
   *
   *  Models the Witt-vector operator `V` for `p = 2`.  Contracts the evaluation
   *  point toward the Gauss point `w = 0` by a factor of `1/2`.  After `n`
   *  steps: `weight / 2^n` (verschiebung_iterate).
   *
   *  Equivalently, `verschiebung = (1/2 : ℝ) • id` under the real-module
   *  structure (verschiebung_eq_half_smul).  The Berry phase angle is unchanged
   *  because multiplication by `1/2 > 0` is radially inward (verschiebung_berryPhaseAngle_eq).
   */
  def verschiebung: PhantomCarabiner = copy(weight = weight / 2.0)

  /** Θ-link: rotate the weight by `−π/2` (multiply by `−i`).
   *
   *  `w ↦ −w·i`; on components: `(re, im) ↦ (im, −re)`.
   *
   *  Models the IUT Θ-link, which exchanges the multiplicative theta-value
   *  `Θ(τ)` with an additive normalisation, converting imaginary-axis obstruction
   *  into real-axis height contribution.
   *
   *  Key properties:
   *  - `thetaLink.weight.re = weight.im`   (theta_link_re)
   *  - `thetaLink.weight.im = −weight.re`  (theta_link_im)
   *  - `thetaLink⁴ = id`                  (theta_link_four_id, ℤ₄ holonomy)
   *  - `thetaLink²` negates the weight     (theta_link_sq)
   *  - commutes with `verschiebung`        (verschiebung_theta_link_comm)
   *
   *  WARNING: `complement ∘ thetaLink ≠ thetaLink ∘ complement` in general
   *  (complement is affine anti-linear; thetaLink is linear).  The Lean theorem
   *  `complement_theta_link_comm` is marked `sorry` and left unproven.
   */
  def thetaLink: PhantomCarabiner = copy(weight = weight.mulNegI)

  // ── §5  ℝ-module structure ───────────────────────────────────────────────

  /** Pointwise addition: weights and phases add separately. */
  def +(other: PhantomCarabiner): PhantomCarabiner =
    PhantomCarabiner(weight + other.weight, phase + other.phase)

  /** Scalar multiplication by a real number.
   *
   *  Scales the complex weight; leaves `phase` unchanged.
   *  Corresponds to `SMul ℝ ComplexCarabiner` (smul_weight / smul_phase).
   */
  def scale(r: Double): PhantomCarabiner = copy(weight = weight * r)

  /** Real-weight projection (Berkovich height component).
   *
   *  Additive, compatible with scalar multiplication (reWeight_add / reWeight_smul):
   *  - `(a + b).reWeight = a.reWeight + b.reWeight`
   *  - `c.scale(r).reWeight = r * c.reWeight`
   *
   *  Under verschiebung: `verschiebung.reWeight = reWeight / 2`  (reWeight_verschiebung).
   *  Under thetaLink:    `thetaLink.reWeight = weight.im`       (reWeight_theta_link).
   */
  def reWeight: Double = weight.re

  // ── §7  Berry phase ──────────────────────────────────────────────────────

  /** Berry phase angle: `arg(weight)` (principal value in `(−π, π]`).
   *
   *  Measures the phase accumulated along the Berry connection `A = Im(dw/w)`.
   *  - Equals `0` on the positive real axis (real_weight_berryPhase_eq_zero).
   *  - Advances by `−π/2` under each `thetaLink` application.
   *  - Unchanged by `verschiebung` for `weight ≠ 0` (verschiebung_berryPhaseAngle_eq).
   *  - Negated (time-reversed) by `complement` (complement is anti-unitary).
   */
  def berryPhaseAngle: Double = weight.arg

  /** Squared modulus `|weight|² = re² + im²`.
   *
   *  Gauge-invariant under the adiabatic connection:
   *  - Preserved by `thetaLink` (unitary rotation, theta_link_preserves_normSq).
   *  - Scaled by `1/4` under `verschiebung` (verschiebung_normSq).
   *  - Preserved by `n` iterations of `thetaLink` (theta_link_iterate_normSq).
   */
  def weightNormSq: Double = weight.normSq

  // ── §8  Third Chern class shadow ─────────────────────────────────────────

  /** Imaginary part of `thetaLink.weight` equals `−weight.re`.
   *
   *  In the c₃ shadow picture: at `affine_dual` (height = 4 = selfIntersection²),
   *  this gives `−4 = −c₃Eval(BWWeight.rank.w4)`.
   *  (theta_link_im_eq_neg_height / theta_link_im_neg_of_height)
   */
  def thetaLinkImNegHeight: Double = -weight.re

// ─── §1b  PhantomCarabiner companion ─────────────────────────────────────────

object PhantomCarabiner:

  /** The zero phantom carabiner (Gauss point, phase 0). */
  val zero: PhantomCarabiner = PhantomCarabiner(ComplexWeight.zero, phase = 0)

  /** Lift a discrete `Carabiner` to a phantom carabiner.
   *
   *  The Golay orbit height becomes the real Berkovich height; the obstruction
   *  residual starts at `0` (purely real, zero Berry phase).
   *  The space topology tag is a derived property of the weight (not a field),
   *  so no information is lost in the lift. */
  def fromCarabiner(c: Carabiner): PhantomCarabiner =
    PhantomCarabiner(
      weight = ComplexWeight.fromGolayWeight(c.weight),
      phase  = c.phase
    )

  /** Iterate Verschiebung `n` times in O(1): `weight / 2^n`. */
  def verschiebungN(c: PhantomCarabiner, n: Int): PhantomCarabiner =
    require(n >= 0, s"verschiebung step count must be ≥ 0, got $n")
    c.copy(weight = c.weight / pow(2.0, n.toDouble))

  /** Iterate thetaLink `n mod 4` times (exploits ℤ₄ periodicity). */
  def thetaLinkN(c: PhantomCarabiner, n: Int): PhantomCarabiner =
    (n % 4) match
      case 0 => c
      case 1 => c.thetaLink
      case 2 => c.thetaLink.thetaLink
      case 3 => c.thetaLink.thetaLink.thetaLink
      case _ => c   // unreachable; satisfies exhaustiveness

  // ── §6  SpiralRotation bridge ────────────────────────────────────────────

  /** Lift a `SpiralRotation` to a phantom carabiner via the Satake spectral parameter.
   *
   *  The Satake parameter `u = λ · exp(iθ)` becomes the complex Berkovich weight:
   *  - `weight.re = λ_re · cos θ − λ_im · sin θ`
   *  - `weight.im = λ_re · sin θ + λ_im · cos θ`
   *
   *  This is the composition `PhantomCarabiner ∘ spiralToSpectralParam` from
   *  `YangBaxterBanach.lean` §8.  Key identities (verschiebung_spiral / theta_link_spiral):
   *  - `fromSpiralRotation(s).verschiebung` = same as halving `s.scalingRe` and `s.scalingIm`
   *  - `fromSpiralRotation(s).thetaLink`    = rotating spectral parameter by −π/2
   */
  def fromSpiralRotation(s: SpiralRotation, phase: Int = 4): PhantomCarabiner =
    val (re, im) = spiralToSpectralParam(s)
    PhantomCarabiner(ComplexWeight(re, im), phase)

// ─── §Ref  PhantomCarabinerRef ───────────────────────────────────────────────

/** Opaque `Ref` wrapper for a mutable phantom carabiner.
 *
 *  Addresses the original `Ref[IO, Array[Byte]]` concern: the raw
 *  `Ref[IO, PhantomCarabiner]` is hidden behind a sealed update API so callers
 *  cannot perform arbitrary mutations that break the algebraic invariants.
 *
 *  All exposed operations are algebra-preserving (complement, verschiebung,
 *  thetaLink) or read-only projections.
 */
opaque type PhantomCarabinerRef = Ref[IO, PhantomCarabiner]

object PhantomCarabinerRef:

  def make(initial: PhantomCarabiner): IO[PhantomCarabinerRef] =
    Ref.of[IO, PhantomCarabiner](initial)

  def makeZero: IO[PhantomCarabinerRef] = make(PhantomCarabiner.zero)

  def fromCarabiner(c: Carabiner): IO[PhantomCarabinerRef] =
    make(PhantomCarabiner.fromCarabiner(c))

  extension (ref: PhantomCarabinerRef)

    // ── read-only projections ──────────────────────────────────────────────
    def read: IO[PhantomCarabiner]       = ref.get
    def reWeight: IO[Double]             = ref.get.map(_.reWeight)
    def berryPhaseAngle: IO[Double]      = ref.get.map(_.berryPhaseAngle)
    def weightNormSq: IO[Double]         = ref.get.map(_.weightNormSq)

    // ── algebra-preserving updates ─────────────────────────────────────────
    def applyComplement: IO[Unit]        = ref.update(_.complement)
    def applyVerschiebung: IO[Unit]      = ref.update(_.verschiebung)
    def applyVerschiebungN(n: Int): IO[Unit] =
      ref.update(c => PhantomCarabiner.verschiebungN(c, n))
    def applyThetaLink: IO[Unit]         = ref.update(_.thetaLink)
    def applyThetaLinkN(n: Int): IO[Unit] =
      ref.update(c => PhantomCarabiner.thetaLinkN(c, n))
    def applyScale(r: Double): IO[Unit]  = ref.update(_.scale(r))

    // ── modify-and-read ────────────────────────────────────────────────────
    def modifyGet[A](f: PhantomCarabiner => (PhantomCarabiner, A)): IO[A] =
      ref.modify(f)

    // ── escape hatch (internal use only — not exported in public API) ──────
    private[carabiner] def unsafeUpdate(f: PhantomCarabiner => PhantomCarabiner): IO[Unit] =
      ref.update(f)
