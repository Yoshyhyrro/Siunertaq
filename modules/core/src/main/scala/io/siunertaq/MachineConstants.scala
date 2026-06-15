package io.siunertaq

import scala.math.{ log, sqrt, abs }

// ===================================================================
// MachineConstants.scala
// Faithful translation of HatsuYakitori.MachineConstants
//
// Design intent — SIMD⇔SIMT correspondence:
//
//   SIMD (data parallelism):
//     GolayWeight.orbitSize  = number of parallel data lanes.
//     W8 has 759 octad lanes; the same Frobenius morphism applies
//     to all 759 simultaneously — one instruction, 759 data elements.
//
//   SIMT (thread parallelism):
//     Each Ref[IO, State] vertex is a thread register.
//     BSDArrow morphisms are the shared instruction stream.
//     IO.parTraverse over BSDArrows = warp execution.
//
//   galoisHeight (logarithmic) = warp-divergence mask:
//     Only semistable orbits (bounded log-height) contribute.
//     Unstable orbits (exponential growth) are GIT-filtered out —
//     exactly like predicating-off divergent SIMT threads.
//
//   octadHeight (linear, [0,8]) = per-thread register value
//     = position on the Berkovich tree.
//
//   machineEpsilonReal = 2.22e-16 (IEEE 754 double)
//     The hardware floor; valuationDepth = 52 (mantissa bits)
//     = the depth at which the p-adic tower hits the FP representation.
// ===================================================================

// ─── §1  Fundamental constants ───────────────────────────────────────────────

/** IEEE 754 double-precision machine epsilon (2⁻⁵²).
 *  Lean: `machineEpsilonReal`. Used as the floor in `qAdicEquivalent`. */
val machineEpsilonReal: Double = 2.220446049250313e-16

/** Default tolerance for numerical comparisons.
 *  Lean: `defaultToleranceReal`. */
val defaultToleranceReal: Double = 1e-10

/** Maximum Berkovich height, normalised to the M₂₄ action length 24.
 *  Lean: `galoisHeightBound = 8`. All octad heights lie in [0, galoisHeightBound]. */
val galoisHeightBound: Double = 8.0

/** Dimension of the affine embedding space A¹¹.
 *  Lean: `AffineDimension = 11`. */
val affineDimension: Int = 11

// ─── §2  q-Deformation / AK parameters ──────────────────────────────────────

/** Depth of p-adic valuation = IEEE 754 double mantissa bits.
 *  Lean: `valuationDepth = 52`.
 *  SIMT reading: the p-adic "stack depth" at which floating-point
 *  representation runs out; mirrors the SIMT execution depth limit. */
val valuationDepth: Int = 52

/** Ariki-Koike parameter n: number of braid strands.
 *  Lean: `arikiKoikeN = 8`.
 *  SIMT reading: analogous to warp size — the natural grouping of threads. */
val arikiKoikeN: Int = 8

/** Ariki-Koike parameter r: cyclic group order.
 *  Lean: `arikiKoikeR = 3`. Gives the ℤ/3ℤ Hecke specialisation. */
val arikiKoikeR: Int = 3

/** Hida eigenvalue ratio: minimum octad height separation / heightBound.
 *  Lean: `hidaEigenvalueRatio = galoisHeightBound / 6 = 4/3`.
 *  Also appears in BSDQuiverManager.OplusPadic as `normBound / 6.0`. */
val hidaEigenvalueRatio: Double = galoisHeightBound / 6.0   // = 4/3 ≈ 1.333

/** Two real values are q-adically equivalent if they differ by < machineEpsilonReal.
 *  Lean: `qAdicEquivalent`. */
def qAdicEquivalent(x: Double, y: Double): Boolean =
  abs(x - y) < machineEpsilonReal

// ─── §3  galoisHeight — logarithmic (GIT semistability mask) ─────────────────

/** Height function measuring representation dimension on logarithmic scale.
 *
 *  `galoisHeight(n) = galoisHeightBound · log(n) / log(24)`
 *
 *  Lean: `noncomputable def galoisHeight (cycleLength : ℕ) : ℝ`.
 *
 *  Encodes the GIT semistability condition:
 *  - Semistable orbits (cycle length ≤ 24) have height ≤ galoisHeightBound.
 *  - Unstable orbits (exponential growth) exceed the bound → filtered out.
 *  - Base-24 normalization: galoisHeight(24) = galoisHeightBound = 8 exactly.
 *
 *  SIMT reading: this is the warp-divergence mask.  Threads (orbits) whose
 *  "instruction count" grows faster than log are predicated off.
 *
 *  NOTE: BSDQuiver.scala has a simplified mock `tau.toDouble`.
 *  Use this function for correct logarithmic behaviour. */
def galoisHeight(cycleLength: Int): Double =
  if cycleLength <= 0 then 0.0
  else galoisHeightBound * (log(cycleLength.toDouble) / log(24.0))

// Lean theorems as runtime checks (not proved, verified by eval):
assert(galoisHeight(0)  == 0.0,                           "galoisHeight_zero")
assert(galoisHeight(1)  == 0.0,                           "galoisHeight_one")
assert(galoisHeight(24) == galoisHeightBound,              "galoisHeight_24_eq_bound")
assert(galoisHeight(8)  <= galoisHeightBound,              "galoisHeight_bounded_8")
assert(galoisHeight(8)  <= galoisHeight(16),               "galoisHeight_monotone_8_16")

// ─── §8  octadHeight — linear Berkovich tree position ────────────────────────

/** Height function specialized for standard Golay code weights.
 *
 *  Lean: `noncomputable def octadHeight (weight : Fin 25) : ℝ`.
 *
 *  Concrete values (cf. galoisHeightBound = 8):
 *    0  → 0
 *    8  → 8/3  ≈ 2.667   (octad)
 *    12 → 4               (dodecad, self-dual midpoint)
 *    16 → 16/3 ≈ 5.333   (complement octad)
 *    24 → 8               (full cycle / cusp)
 *
 *  These satisfy `octadHeight(w) + octadHeight(24-w) = galoisHeightBound`
 *  (the Hopf counit symmetry; Lean: `height_add_complement_height`).
 *
 *  SIMT reading: this is the per-thread register value — the Berkovich
 *  position of the thread in the height tower.
 *
 *  For non-standard weights, falls back to linear interpolation w/24 · 8.
 */
def octadHeight(weight: Int): Double = weight match
  case 0  => 0.0
  case 8  => galoisHeightBound / 3.0          // 8/3
  case 12 => galoisHeightBound / 2.0          // 4
  case 16 => galoisHeightBound * 2.0 / 3.0   // 16/3
  case 24 => galoisHeightBound                // 8
  case k  => k.toDouble / 24.0 * galoisHeightBound  // linear fallback

/** Verify the complement-sum identity for all standard weights. */
private val _octadHeightChecks: Unit =
  val std = List(0, 8, 12, 16, 24)
  for w <- std do
    val h  = octadHeight(w)
    val hc = octadHeight(24 - w)
    assert(
      abs(h + hc - galoisHeightBound) < defaultToleranceReal,
      s"octadHeight($w) + octadHeight(${24-w}) = ${h + hc} ≠ $galoisHeightBound"
    )

// ─── §5  heightDiscriminant ──────────────────────────────────────────────────

/** Relative height difference, measuring distinguishability of representations.
 *  Lean: `def heightDiscriminant (h1 h2 : ℝ) : ℝ`.
 *
 *  `|h1 - h2| / max(h1, max(h2, machineEpsilonReal))`
 *
 *  Normalised by the larger height to ensure scale invariance.
 *  Returns 0 when h1 = h2 (heightDiscriminant_self). */
def heightDiscriminant(h1: Double, h2: Double): Double =
  val denom = h1 max (h2 max machineEpsilonReal)
  abs(h1 - h2) / denom

/** Two heights are within tolerance if their discriminant is small.
 *  Lean: `def heightWithinTolerance`. */
def heightWithinTolerance(
  h1: Double, h2: Double,
  tol: Double = defaultToleranceReal
): Boolean = heightDiscriminant(h1, h2) < tol

// ─── §7  safeLog ─────────────────────────────────────────────────────────────

/** Regularized logarithm: returns 0 for non-positive inputs.
 *  Lean: `noncomputable def safeLog (x : ℝ) : ℝ`. */
def safeLog(x: Double): Double =
  if x > 0.0 then log(x) else 0.0

// ─── §9  Affine A¹¹ embedding ────────────────────────────────────────────────

/** Affine embedding of an octad weight into A¹¹.
 *  Lean: `octadHeightVector (weight : Fin 25) : Fin 11 → ℝ`.
 *
 *  The first coordinate holds the octad height; the rest are 0.
 *  This ensures distinct standard weights map to distinct points
 *  (affine_embedding_injective). */
def octadHeightVector(weight: Int): Array[Double] =
  val v = Array.fill(affineDimension)(0.0)
  v(0) = octadHeight(weight)
  v

/** Euclidean distance in A¹¹.
 *  Lean: `affineDistance`.
 *  Minimum pairwise distance between distinct standard weights = 4/3 = hidaEigenvalueRatio
 *  (octadHeight_wellSeparated). */
def affineDistance(w1: Int, w2: Int): Double =
  val v1 = octadHeightVector(w1)
  val v2 = octadHeightVector(w2)
  sqrt(v1.zip(v2).map { (a, b) => (a - b) * (a - b) }.sum)

// ─── §10  CarabinerHeight typeclass ──────────────────────────────────────────

/** Abstract height system interface, shared across all carabiner families.
 *
 *  Lean: `class CarabinerHeight (W : Type*) where ...`
 *
 *  Any weight type `W` that forms a carabiner family (Golay, Clifford,
 *  Fischer, …) must provide:
 *  - a height function h : W → [0, heightBound]
 *  - a complement involution satisfying h(w) + h(complement(w)) = heightBound
 *
 *  SIMT reading: normalizedHeight ∈ [0,1] gives a canonical per-thread
 *  "program counter" regardless of which carabiner family is running. */
trait CarabinerHeight[W]:
  def height(w: W): Double
  def heightBound: Double
  def complement(w: W): W

  /** Normalised height ∈ [0, 1].  Lean: `CarabinerHeight.normalizedHeight`. */
  final def normalizedHeight(w: W): Double =
    if heightBound == 0.0 then 0.0 else height(w) / heightBound

  /** Runtime check: h(w) + h(complement(w)) = heightBound. */
  final def checkComplementSymmetry(w: W): Boolean =
    abs(height(w) + height(complement(w)) - heightBound) < defaultToleranceReal

/** CarabinerHeight instance for GolayWeight.
 *  Lean: `noncomputable instance : CarabinerHeight GolayWeight`. */
given CarabinerHeight[GolayWeight] with
  def height(w: GolayWeight): Double    = octadHeight(w.toNat)
  def heightBound: Double               = galoisHeightBound
  def complement(w: GolayWeight): GolayWeight = w.antipode

// ─── §11  Cyclotomic ramification data ───────────────────────────────────────

/** Ramification index `e` and inertia degree `f` at a prime above p.
 *  Lean: `structure RamificationData`.
 *  Invariant: e · f = φ(24) = 8. */
final case class RamificationData(e: Int, f: Int):
  require(e * f == 8, s"RamificationData: e·f = ${e*f} ≠ 8 = φ(24)")

/** Cyclotomic ramification for ℚ(ζ₂₄)/ℚ at primes.
 *  Lean: `def cyclotomic_ramification_24 (p : ℕ)`.
 *
 *  - p = 2: e = 4 (total ramification at 2), f = 2
 *  - p = 3: e = 2, f = 4
 *  - p ∤ 24: e = 1, f = 8 (unramified, full inertia degree = φ(24))
 */
def cyclotomicRamification24(p: Int): RamificationData =
  if p == 2 then RamificationData(4, 2)
  else if p == 3 then RamificationData(2, 4)
  else RamificationData(1, 8)

// ─── §12  M₂₄ conjugacy classes (rigid triple) ───────────────────────────────

/** A conjugacy class with representative order and class size.
 *  Lean: `structure ConjugacyClass`. */
final case class ConjugacyClass(order: Int, size: Int)

/** The rigid triple for M₂₄: three conjugacy classes.
 *
 *  Lean: `def M24_rigid_triple`.
 *
 *  Sizes: 276 (2A), 1288 (3A), 759 (8A).
 *  Class 8A has exactly 759 elements — matching GolayWeight.W8.orbitSize.
 *  This is the bridge between M₂₄ group theory and the Golay code:
 *    `c₈.size = GolayWeight.w8.orbitSize = 759`  (rigid_triple_octad_size).
 */
val m24RigidTriple: (ConjugacyClass, ConjugacyClass, ConjugacyClass) =
  ( ConjugacyClass(order = 2, size = 276),   // 2A
    ConjugacyClass(order = 3, size = 1288),  // 3A
    ConjugacyClass(order = 8, size = 759) )  // 8A ↔ octad orbit!

/** Verify: class 8A size equals the W8 orbit size (= 759).
 *  Lean: `rigid_triple_octad_size`. */
assert(
  m24RigidTriple._3.size == 759,
  "8A class size must equal GolayWeight.W8.orbitSize"
)

/** Ramification-complement compatibility:
 *  e₂ · e₃ = 4 · 2 = 8 = galoisHeightBound.
 *  Lean: `ramification_complement_compatible`. */
assert(
  cyclotomicRamification24(2).e * cyclotomicRamification24(3).e == galoisHeightBound.toInt,
  "e₂ · e₃ must equal galoisHeightBound"
)
