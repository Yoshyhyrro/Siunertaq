package io.siunertaq.carabiner

import cats.effect.{ IO, Ref }
import io.siunertaq.GolayWeight   // canonical definition lives in BSDQuiver.scala

// ─── §0  GolayWeight extensions ──────────────────────────────────────────────
// GolayWeight is defined in io.siunertaq (BSDQuiver.scala) with:
//   toNat: Int       — the weight value (0, 8, 12, 16, 24)
//   antipode         — Lean's "complement"
// These extensions add what Carabiner.lean needs without touching BSDQuiver.

extension (gw: GolayWeight)

  /** Number of codewords of this weight class in the extended Golay code.
   *  Lean: `GolayWeight.orbitSize`.  Sum = 1+759+2576+759+1 = 4096 = 2¹². */
  def orbitSize: Int = gw match
    case GolayWeight.W0  => 1
    case GolayWeight.W8  => 759
    case GolayWeight.W12 => 2576
    case GolayWeight.W16 => 759
    case GolayWeight.W24 => 1

  /** Real Berkovich tree height in [0, 8]: `toNat / 3`.
   *  Lean: `GolayWeight.height` via `galoisHeightBound = 8`.
   *  Note: BSDQuiver.galoisHeight uses `tau.toDouble` (unscaled mock).
   *  Here we use the geometrically correct value: toNat / 3. */
  def berkovichHeight: Double = gw.toNat.toDouble / 3.0

  /** Lean naming alias for `antipode`.
   *  `antipode` is the existing BSDQuiver.scala method; `complement` is
   *  the name used throughout Carabiner.lean. */
  def complement: GolayWeight = gw.antipode

  /** Verify self-duality: toNat + complement.toNat = 24 for all weights. */
  def complementSumCheck: Int = gw.toNat + gw.antipode.toNat   // always 24

// ─── §1  SpaceTag ────────────────────────────────────────────────────────────
// In the Lean, SpaceTag is imported from HatsuYakitori.BSDQuiver.
// The current BSDQuiver.scala doesn't define it yet, so it lives here temporarily.
// TODO: move to BSDQuiver.scala when convenient.

/** Berkovich topology tag derived from the Golay weight.
 *
 *  Lean: `SpaceTag` from `HatsuYakitori.BSDQuiver`.
 *  SpaceTag sequence of golayRoute: [Affine, Banach, Hybrid, Banach, Affine]
 *  — palindromic (golayRoute_tags_palindromic).
 *
 *  Corresponds to BSDVertex roles:
 *    Leech  (W0)  → Affine,  AffineDual (W12) → Hybrid,
 *    Padic  (W8)  → Banach,  Selmer     (W16) → Banach,
 *    W24 (cusp)   → Affine   (no BSDVertex; recession fan terminus)
 */
enum SpaceTag derives CanEqual:
  case Affine   // W0 (base) and W24 (cusp) — algebraic / Archimedean endpoints
  case Banach   // W8 (octad) and W16 (complement octad) — p-adic completion
  case Hybrid   // W12 (dodecad) — self-dual midpoint, unique fixed point

// ─── §2  TransformEffect ─────────────────────────────────────────────────────
// In the Lean, TransformEffect is also from HatsuYakitori.BSDQuiver.
// TODO: move to BSDQuiver.scala when convenient.

/** Effect of a single step in the forward / recession Carabiner fan.
 *
 *  Lean: `TransformEffect` from `HatsuYakitori.BSDQuiver`.
 *  XZ involution: AddsTopology ∘ ForegtsTopology = PreservesAlgebraic
 *  (recession_xz_cancellation). */
enum TransformEffect derives CanEqual:
  case AddsTopology        // w0→w8  (completion)  / w16→w24 (completion)
  case ForegtsTopology     // w24→w16 (algebraize) / w8→w0   (algebraize)
  case MixesStructures     // w8↔w12, w12↔w16  (hybridization)
  case PreservesAlgebraic  // identity: AddsTopology ∘ ForegtsTopology

object TransformEffect:
  /** Lean: `combineEffects`. */
  def combine(a: TransformEffect, b: TransformEffect): TransformEffect =
    (a, b) match
      case (AddsTopology,    ForegtsTopology) |
           (ForegtsTopology, AddsTopology)    => PreservesAlgebraic
      case (MixesStructures, MixesStructures) => MixesStructures
      case _                                  => MixesStructures

// ─── §3  Carabiner ───────────────────────────────────────────────────────────

/** A carabiner: a Golay weight together with a ℤ/4ℤ Pauli phase.
 *
 *  Lean: `structure Carabiner where weight : GolayWeight ; phase : ZMod 4 := 0`.
 *
 *  The pair `(weight, phase)` approximates a complex evaluation point
 *  `s = berkovichHeight(weight) + (π/2)·phase` on the critical strip.
 *
 *  @param weight  Golay lattice point (type from `io.siunertaq.GolayWeight`).
 *  @param phase   ℤ/4ℤ Pauli phase stored as `Int` ∈ {0,1,2,3}.
 *                 0 ↔ 1,  1 ↔ i,  2 ↔ −1,  3 ↔ −i.
 */
final case class Carabiner(
  weight: GolayWeight,
  phase:  Int = 0        // invariant: 0 ≤ phase < 4
) derives CanEqual:

  /** Real Berkovich height = `weight.berkovichHeight`. */
  def height: Double = weight.berkovichHeight

  /** Orbit size at this lattice point. */
  def orbitSize: Int = weight.orbitSize

  /** Space topology tag, *derived* from weight — not a constructor field.
   *  Lean: `def Carabiner.spaceTag`. */
  def spaceTag: SpaceTag = weight match
    case GolayWeight.W0  | GolayWeight.W24 => SpaceTag.Affine
    case GolayWeight.W8  | GolayWeight.W16 => SpaceTag.Banach
    case GolayWeight.W12                   => SpaceTag.Hybrid

  /** Complement: reflect height across the self-dual midpoint and negate phase.
   *
   *  Lean: `def Carabiner.complement (c : Carabiner) := ⟨c.weight.complement, -c.phase⟩`.
   *  In ℂ: `S(s) = (K − h) + (π/2)(−φ)`, so `s + S(s) = K` (functional eq.). */
  def complement: Carabiner =
    Carabiner(weight.antipode, Math.floorMod(-phase, 4))

  /** True iff this is its own complement (only W12 with phase=0 qualifies). */
  def isSelfDual: Boolean = complement == this

  /** True iff this is an S(5,8,24) octad carabiner. */
  def isOctad: Boolean = weight == GolayWeight.W8

  /** True iff this is a dodecad carabiner. */
  def isDodecad: Boolean = weight == GolayWeight.W12

object Carabiner:
  /** Standard phase-0 carabiners (real-axis evaluation points). */
  val c0  : Carabiner = Carabiner(GolayWeight.W0)
  val c8  : Carabiner = Carabiner(GolayWeight.W8)
  val c12 : Carabiner = Carabiner(GolayWeight.W12)
  val c16 : Carabiner = Carabiner(GolayWeight.W16)
  val c24 : Carabiner = Carabiner(GolayWeight.W24)

  /** Construct with phase normalised into [0, 3].
   *  Lean: `def Carabiner.withPhase (w : GolayWeight) (φ : ZMod 4)`. */
  def withPhase(w: GolayWeight, phi: Int): Carabiner =
    Carabiner(w, Math.floorMod(phi, 4))

// ─── §4  Route ───────────────────────────────────────────────────────────────

/** Ordered list of carabiners: a path in the Berkovich tree.
 *  Lean: `abbrev Route := List Carabiner`. */
type Route = List[Carabiner]

object Route:

  /** Lean: `def Route.totalPositions`. */
  def totalPositions(r: Route): Int = r.foldLeft(0)(_ + _.orbitSize)

  /** Lean: `def Route.isAscending`. */
  def isAscending(r: Route): Boolean =
    r.zip(r.drop(1)).forall((a, b) => a.height <= b.height)

  /** Lean: `def Route.isDescending`. */
  def isDescending(r: Route): Boolean =
    r.zip(r.drop(1)).forall((a, b) => a.height >= b.height)

  /** Lean: `def Route.complement := r.reverse.map Carabiner.complement`. */
  def complement(r: Route): Route = r.reverse.map(_.complement)

  /** Lean: `def Route.recessionFan := r.complement`. */
  def recessionFan(r: Route): Route = complement(r)

  /** Extract weight sequence.  Lean: `def Route.weights`. */
  def weights(r: Route): List[GolayWeight] = r.map(_.weight)

  /** Extract SpaceTag sequence.  Lean: `golayRoute_tags_palindromic`. */
  def spaceTags(r: Route): List[SpaceTag] = r.map(_.spaceTag)

  /** Lean: `Route.isAKGenerated := isAscending ∧ ∀ c ∈ r, c.phase = 0`. */
  def isAKGenerated(r: Route): Boolean =
    isAscending(r) && r.forall(_.phase == 0)

  /** Lean: `Route.isGolayLike` condition (A). */
  def isSelfDual(r: Route): Boolean = r.forall(c => r.contains(c.complement))

  /** Goppa distance lower bound = route length. */
  def distanceLowerBound(r: Route): Int = r.length

  /** Forward fan effect for step i (0-based). Lean: `forwardStepEffect`. */
  def forwardStepEffect(i: Int): TransformEffect = i match
    case 0 => TransformEffect.AddsTopology    // W0 → W8
    case 1 => TransformEffect.MixesStructures // W8 → W12
    case 2 => TransformEffect.MixesStructures // W12 → W16
    case 3 => TransformEffect.AddsTopology    // W16 → W24
    case _ => TransformEffect.PreservesAlgebraic

  /** Recession fan effect for step i (0-based). Lean: `recessionStepEffect`. */
  def recessionStepEffect(i: Int): TransformEffect = i match
    case 0 => TransformEffect.ForegtsTopology // W24 → W16
    case 1 => TransformEffect.MixesStructures // W16 → W12
    case 2 => TransformEffect.MixesStructures // W12 → W8
    case 3 => TransformEffect.ForegtsTopology // W8 → W0
    case _ => TransformEffect.PreservesAlgebraic

// ─── §5  golayRoute ──────────────────────────────────────────────────────────

/** The canonical five-carabiner route through all Golay weights.
 *
 *  Lean: `def golayRoute := [carabiner0, carabiner8, carabiner12, carabiner16, carabiner24]`.
 *  - Length 5                                     (golayRoute_length)
 *  - Total positions = 4096 = 2¹²                (golayRoute_total_positions)
 *  - Ascending heights                            (golayRoute_ascending)
 *  - Self-complementary: recessionFan = self      (golayRoute_recession_self_dual)
 *  - Palindromic orbit sizes [1,759,2576,759,1]   (golayRoute_palindrome)
 *  - Palindromic SpaceTag [Affine,Banach,Hybrid,Banach,Affine] (tags_palindromic)
 *  - Fan equation: h[i] + h[4−i] = 8             (fan_functional_equation)
 */
val golayRoute: Route = List(
  Carabiner.c0,
  Carabiner.c8,
  Carabiner.c12,
  Carabiner.c16,
  Carabiner.c24
)

/** Runtime verification of all golayRoute structural properties. */
object GolayRouteChecks:
  lazy val length          : Boolean = golayRoute.length == 5
  lazy val totalPositions  : Boolean = Route.totalPositions(golayRoute) == 4096
  lazy val isAscending     : Boolean = Route.isAscending(golayRoute)
  lazy val isSelfDual      : Boolean = Route.isSelfDual(golayRoute)
  lazy val recessionIsSelf : Boolean = Route.recessionFan(golayRoute) == golayRoute
  lazy val palindromeOrbits: Boolean =
    golayRoute.map(_.orbitSize) == golayRoute.map(_.orbitSize).reverse
  lazy val palindromeTags  : Boolean =
    golayRoute.map(_.spaceTag) == golayRoute.map(_.spaceTag).reverse
  lazy val allChecks       : Boolean =
    length && totalPositions && isAscending &&
    isSelfDual && recessionIsSelf && palindromeOrbits && palindromeTags
