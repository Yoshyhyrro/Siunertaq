package io.siunertaq

import scala.math.{ log, sqrt, abs }

// ===================================================================
// MachineConstants.scala
// Faithful translation of HatsuYakitori.MachineConstants
//
// All definitions live inside object MachineConstants to avoid two
// problems:
//   1. galoisHeight is already defined as a top-level mock in
//      BSDQuiver.scala.  Wrapping here keeps namespaces separate;
//      call sites that want the correct logarithmic formula use
//      MachineConstants.galoisHeight(...).
//   2. Bare assert(...) is illegal at Scala 3 package (top-level)
//      scope.  Inside an object the body is the constructor, so
//      assertions run at first access.
// ===================================================================

object MachineConstants:

  // ─── §1  Fundamental constants ─────────────────────────────────────────────

  /** IEEE 754 double-precision machine epsilon (2⁻⁵²).
   *  Lean: `machineEpsilonReal`. Used as hardware floor in `qAdicEquivalent`. */
  val machineEpsilonReal: Double = 2.220446049250313e-16

  /** Default tolerance for numerical comparisons.
   *  Lean: `defaultToleranceReal`. */
  val defaultToleranceReal: Double = 1e-10

  /** Maximum Berkovich height for the M₂₄ action (normalised to 24-cycle).
   *  Lean: `galoisHeightBound = 8`.  All octad heights lie in [0, 8]. */
  val galoisHeightBound: Double = 8.0

  /** Dimension of the affine embedding space A¹¹.
   *  Lean: `AffineDimension = 11`. */
  val affineDimension: Int = 11

  // ─── §2  q-Deformation / AK parameters ──────────────────────────────────

  /** Depth of p-adic valuation = IEEE 754 double mantissa bits.
   *  Lean: `valuationDepth = 52`.
   *  SIMT reading: the execution depth at which floating-point precision
   *  runs out; the p-adic "stack" bottoms out at the hardware floor. */
  val valuationDepth: Int = 52

  /** Ariki-Koike parameter n: number of braid strands.
   *  Lean: `arikiKoikeN = 8`.
   *  SIMT reading: analogous to warp size — the natural grouping unit. */
  val arikiKoikeN: Int = 8

  /** Ariki-Koike parameter r: cyclic group order.
   *  Lean: `arikiKoikeR = 3`.  ℤ/3ℤ Hecke specialisation. */
  val arikiKoikeR: Int = 3

  /** Hida eigenvalue ratio: minimum octad height separation / heightBound.
   *  Lean: `hidaEigenvalueRatio = galoisHeightBound / 6 = 4/3`.
   *  Also appears in BSDQuiverManager.OplusPadic as `normBound / 6.0`. */
  val hidaEigenvalueRatio: Double = galoisHeightBound / 6.0   // = 4/3

  /** Two values are q-adically equivalent if they differ by < machineEpsilonReal.
   *  Lean: `qAdicEquivalent`. */
  def qAdicEquivalent(x: Double, y: Double): Boolean =
    abs(x - y) < machineEpsilonReal

  // ─── §3  galoisHeight — logarithmic (GIT semistability mask) ─────────────
  //
  //   NOTE: BSDQuiver.scala defines a *mock*
  //     def galoisHeight(tau: Int): Double = tau.toDouble
  //   at package level for the DP weight accumulation.
  //   THIS is the correct logarithmic formula from MachineConstants.lean.
  //   Use MachineConstants.galoisHeight(...) at call sites that need it.
  //
  //   SIMD⇔SIMT reading:
  //     galoisHeight is the warp-divergence mask.  Orbits whose "instruction
  //     count" grows faster than log are GIT-filtered (predicated off).
  //     Base-24 normalisation: galoisHeight(24) = galoisHeightBound exactly.

  /** `galoisHeightBound · log(n) / log(24)`.
   *  Lean: `noncomputable def galoisHeight (cycleLength : ℕ) : ℝ`. */
  def galoisHeight(cycleLength: Int): Double =
    if cycleLength <= 0 then 0.0
    else galoisHeightBound * (log(cycleLength.toDouble) / log(24.0))

  // ─── §8  octadHeight — linear Berkovich tree position ────────────────────
  //
  //   octadHeight is SEPARATE from galoisHeight:
  //     octadHeight(w) = w / 3            (linear, per-thread register value)
  //     galoisHeight(n) = 8·log(n)/log(24) (logarithmic, semistability mask)
  //
  //   Concrete values with galoisHeightBound = 8:
  //     weight  0 → 0,  8 → 8/3,  12 → 4,  16 → 16/3,  24 → 8
  //
  //   Complement symmetry (height_add_complement_height):
  //     octadHeight(w) + octadHeight(24 - w) = galoisHeightBound   for all w.

  /** `galoisHeightBound · weight / 24`.
   *  Lean: `noncomputable def octadHeight (weight : Fin 25) : ℝ`. */
  def octadHeight(weight: Int): Double = weight match
    case 0  => 0.0
    case 8  => galoisHeightBound / 3.0
    case 12 => galoisHeightBound / 2.0
    case 16 => galoisHeightBound * 2.0 / 3.0
    case 24 => galoisHeightBound
    case k  => k.toDouble / 24.0 * galoisHeightBound

  // ─── §5  heightDiscriminant ───────────────────────────────────────────────

  /** Relative height difference.
   *  Lean: `def heightDiscriminant (h1 h2 : ℝ) : ℝ`. */
  def heightDiscriminant(h1: Double, h2: Double): Double =
    val denom = h1 max (h2 max machineEpsilonReal)
    abs(h1 - h2) / denom

  /** Lean: `def heightWithinTolerance`. */
  def heightWithinTolerance(
    h1: Double, h2: Double,
    tol: Double = defaultToleranceReal
  ): Boolean = heightDiscriminant(h1, h2) < tol

  // ─── §7  safeLog ─────────────────────────────────────────────────────────

  /** Lean: `noncomputable def safeLog`. */
  def safeLog(x: Double): Double = if x > 0.0 then log(x) else 0.0

  // ─── §9  Affine A¹¹ embedding ────────────────────────────────────────────

  /** Lean: `octadHeightVector (weight : Fin 25) : Fin 11 → ℝ`. */
  def octadHeightVector(weight: Int): Array[Double] =
    val v = Array.fill(affineDimension)(0.0)
    v(0) = octadHeight(weight)
    v

  /** Lean: `affineDistance`. */
  def affineDistance(w1: Int, w2: Int): Double =
    val v1 = octadHeightVector(w1)
    val v2 = octadHeightVector(w2)
    sqrt(v1.zip(v2).map { (a, b) => (a - b) * (a - b) }.sum)

  // ─── §10  CarabinerHeight typeclass ──────────────────────────────────────

  /** Abstract height system.
   *  Lean: `class CarabinerHeight (W : Type*)`. */
  trait CarabinerHeight[W]:
    def height(w: W): Double
    def heightBound: Double
    def complement(w: W): W
    final def normalizedHeight(w: W): Double =
      if heightBound == 0.0 then 0.0 else height(w) / heightBound
    final def checkComplementSymmetry(w: W): Boolean =
      abs(height(w) + height(complement(w)) - heightBound) < defaultToleranceReal

  /** CarabinerHeight instance for GolayWeight.
   *  Lean: `noncomputable instance : CarabinerHeight GolayWeight`. */
  given CarabinerHeight[GolayWeight] with
    def height(w: GolayWeight): Double      = octadHeight(w.toNat)
    def heightBound: Double                  = galoisHeightBound
    def complement(w: GolayWeight): GolayWeight = w.antipode

  // ─── §11  Cyclotomic ramification ────────────────────────────────────────

  /** Ramification index e and inertia degree f.
   *  Invariant: e · f = φ(24) = 8.
   *  Lean: `structure RamificationData`. */
  final case class RamificationData(e: Int, f: Int):
    require(e * f == 8, s"RamificationData: e·f = ${e*f} ≠ 8 = φ(24)")

  /** Lean: `def cyclotomic_ramification_24 (p : ℕ)`. */
  def cyclotomicRamification24(p: Int): RamificationData =
    if p == 2      then RamificationData(4, 2)
    else if p == 3 then RamificationData(2, 4)
    else                RamificationData(1, 8)

  // ─── §12  M₂₄ rigid triple ───────────────────────────────────────────────

  final case class ConjugacyClass(order: Int, size: Int)

  /** Three conjugacy classes whose sizes encode the Golay orbits.
   *  Lean: `def M24_rigid_triple`.
   *  Key: 8A class size = 759 = GolayWeight.W8.orbitSize. */
  val m24RigidTriple: (ConjugacyClass, ConjugacyClass, ConjugacyClass) =
    ( ConjugacyClass(order = 2, size = 276),   // 2A
      ConjugacyClass(order = 3, size = 1288),  // 3A
      ConjugacyClass(order = 8, size = 759) )  // 8A ↔ octad orbit

  // ─── Runtime checks (must be inside the object body) ─────────────────────

  private val _checks: Unit =
    assert(galoisHeight(0)  == 0.0,             "galoisHeight(0) must be 0")
    assert(galoisHeight(1)  == 0.0,             "galoisHeight(1) must be 0")
    assert(galoisHeight(24) == galoisHeightBound,"galoisHeight(24) must equal bound")
    assert(galoisHeight(8)  <  galoisHeight(12),"galoisHeight must be monotone")
    val std = List(0, 8, 12, 16, 24)
    for w <- std do
      assert(
        abs(octadHeight(w) + octadHeight(24 - w) - galoisHeightBound) < defaultToleranceReal,
        s"octadHeight complement symmetry failed at $w"
      )
    assert(
      m24RigidTriple._3.size == 759,
      "8A class size must equal W8 orbit size"
    )
    assert(
      cyclotomicRamification24(2).e * cyclotomicRamification24(3).e == galoisHeightBound.toInt,
      "e₂ · e₃ must equal galoisHeightBound"
    )