# Siunertaq

![Yices Threshold CI](https://github.com/Yoshyhyrro/Siunertaq/actions/workflows/yices_test_ci.yml/badge.svg)
![Release](https://github.com/Yoshyhyrro/Siunertaq/actions/workflows/release.yml/badge.svg)

*ᓯᐅᓇᕐᑕᖅ — Inuktitut for "that which lies ahead; a purpose"*

A Scala 3 project for formalising Banach quiver threshold verification and a small arithmetic IR proof pipeline, designed to stay honest about effects and never lie to the type system.

The repository combines:
- a `core` module for build-graph primitives and a minimal arithmetic IR,
- a `z3-bridge` module for Z3-based Banach constraint verification,
- a `yices-bridge` module for Yices 2 SMT cross-checking,
- a `dhall-bridge` module for total Dhall input evaluation via `cats-effect`,
- a `batch-bridge` module for JCL-inspired step orchestration via Apache Pekko and Spring Batch,
- a planned `mlir-bridge` module for future MLIR integration.

## The idea

Build systems are directed graphs. So are Banach spaces with norm-compatible maps. Siunertaq exploits this overlap by modelling build dependencies as arrows in a *BSD quiver* — a directed graph whose vertices carry norm bounds and whose arrows are typed by their role:

- **Frobenius arrows** (`FVRole.Frobenius`) point in the *norm-increasing* direction: forward dependencies, compilation steps, things that must happen.
- **Verschiebung arrows** (`FVRole.Verschiebung`) point in the *norm-decreasing* direction: backward dependencies, cache invalidation, things that propagate failure upstream.

Each `BSDArrow` carries an `IO[Unit]` effect that only fires after an SMT solver confirms the norm constraints are satisfiable. The Dieudonné relation (`selmer.norm × affine.norm = p × leech.norm`) acts as a global coherence condition tying the four standard vertices together.

The goal is to make ill-ordered builds *unrepresentable* rather than merely *ill-advised*.

### Phantom extension: complex Berkovich weights

The discrete quiver structure is lifted to the complex plane through a chain of three types:

```
GolayWeight          — five orbit classes {W0, W8, W12, W16, W24}
  ↓ octadHeight       — linear Berkovich height h ∈ [0, 8]  (h + h̄ = 8)
Carabiner            — (weight: GolayWeight, phase: ℤ/4ℤ)
  ↓ fromCarabiner     — embed at im = 0, carry phase
PhantomCarabiner     — (weight: ComplexWeight, phase: Int)
```

`ComplexWeight` is an opaque type over `(Double, Double)`, hiding the pair behind an
algebra-preserving interface so callers cannot construct malformed weights or mutate
them outside the declared operations:

```scala
opaque type ComplexWeight = (Double, Double)
// re = Berkovich height  (continuous analogue of GolayWeight.octadHeight)
// im = obstruction residual degree  (deviation from the real axis)
```

Three core operations on `PhantomCarabiner` correspond directly to number-theoretic constructions:

| Operation | Formula | Role |
|---|---|---|
| `complement` | `w ↦ 6 − conj(w)` | self-duality across critical midpoint |
| `verschiebung` | `w ↦ w / 2` | Witt-vector operator V for p = 2 |
| `thetaLink` | `w ↦ −w·i` | IUT Θ-link; −π/2 rotation; ℤ₄ holonomy |

Two gauge-invariant observables are exposed as read-only projections:

```scala
def berryPhaseAngle: Double   // arg(weight) ∈ (−π, π]; Berry connection holonomy
def weightNormSq: Double      // |weight|²; preserved by thetaLink, halved by verschiebung²
```

`berryPhaseAngle` advances by exactly −π/2 under each `thetaLink` application and is
unchanged by `verschiebung` (for `weight ≠ 0`), making it a natural invariant for
tracking accumulated phase around a quiver path.

Mutable state is encapsulated behind a second opaque type:

```scala
opaque type PhantomCarabinerRef = Ref[IO, PhantomCarabiner]
// exposed: applyVerschiebung, applyThetaLink, applyComplement, berryPhaseAngle, …
// hidden:  the raw Ref; no arbitrary mutation possible from outside carabiner/
```

### Golay route: the canonical five-step path

`golayRoute` is the unique self-dual ascending path through all five Golay weight classes.
Its structure encodes several simultaneous symmetries:

```
[W0, W8, W12, W16, W24]   orbit sizes: [1, 759, 2576, 759, 1]
SpaceTags: [Affine, Banach, Hybrid, Banach, Affine]   ← palindrome
heights:   [0, 8/3, 4, 16/3, 8]                       ← fan equation h[i] + h[4−i] = 8
totalPositions = 1 + 759 + 2576 + 759 + 1 = 4096 = 2¹²
recessionFan(golayRoute) == golayRoute                 ← self-dual
```

The Mathieu group M₂₄ conjugacy class 8A has exactly 759 elements — matching
`GolayWeight.W8.orbitSize` — which is how M₂₄ group theory enters the route structure.

### Spectral bridge: Yang-Baxter and spiral rotations

`YangBaxterBanach` provides the Satake spectral parameter, bridging the discrete Golay
lattice to the continuous Berkovich tree:

```scala
// SpiralRotation(angle θ, scalingFactor λ ∈ ℂ)
// spiralToSpectralParam: u = λ · exp(iθ)  (Satake torus coordinate)
PhantomCarabiner.fromSpiralRotation(s)   // weight = spectral parameter
```

The spectral R-matrix `R(u)((i₁,i₂),(j₁,j₂)) = u·δᵢ₁ⱼ₁δᵢ₂ⱼ₂ + δᵢ₁ⱼ₂δᵢ₂ⱼ₁` gives
the rational GL₂ Yang-Baxter operator at spectral parameter u.

### Height functions: two scales, two purposes

`MachineConstants` distinguishes two height functions that are frequently conflated:

```
galoisHeight(n) = 8 · log(n) / log(24)     ← logarithmic; GIT semistability mask
octadHeight(w)  = w / 3                     ← linear;      Berkovich tree position

galoisHeight(8)  ≈ 5.23   (representation-theoretic weight)
octadHeight(8)   = 8/3    (geometric position on the tree)
```

The logarithmic function encodes a **GIT stability condition**: orbits whose
representation dimension grows faster than `log` are filtered out, exactly as unstable
SIMT threads are predicated off in a warp.  `machineEpsilonReal = 2⁻⁵²` (IEEE 754)
and `valuationDepth = 52` (mantissa bits) ground the tower in hardware arithmetic.

### SIMD ↔ SIMT correspondence

The algebraic structure maps onto GPU parallelism at two levels:

| GPU concept | Scala / mathematics | Grounding |
|---|---|---|
| SIMD lane | `GolayWeight.orbitSize` (W8 = 759 lanes) | Frobenius acts on all 759 octads simultaneously |
| SIMT thread register | `Ref[IO, State]` per vertex | Per-thread mutable state, atomically updated |
| Warp instruction | `BSDArrow[S, T]` morphism | Same Frobenius/Verschiebung instruction, different data |
| Warp execution | `IO.parTraverse` over arrows | All fibers run concurrently under Cats Effect |
| Thread divergence | `thetaLink` (im ≠ 0) | Phase deviation from the real axis |
| Thread mask | `galoisHeight` semistability | Unstable orbits GIT-filtered before SMT encoding |
| IEEE 754 ε | `machineEpsilonReal` | Floor for `qAdicEquivalent`; p-adic tower bottoms at hardware |
| Warp size | `arikiKoikeN = 8` | Natural braid-strand grouping in AK algebra |
| Mantissa depth | `valuationDepth = 52` | p-adic valuation truncates at the FP representation |

This is the motivation for `Cats Ref` over a simpler mutable variable: `Ref[IO, T]`
is lock-free and referentially transparent, giving the same atomicity guarantee as a
GPU register file without the shared-memory hazards of SIMT divergence.

### Type-safe braid pop on the JVM

`BSDArrow` is a parameterised enum — a phantom-typed directed graph edge:

```scala
enum BSDArrow[Src <: BSDVertex, Tgt <: BSDVertex](
  val src: Src, val tgt: Tgt, val role: FVRole, val effect: IO[Unit])
```

A `List[BSDArrow[? <: BSDVertex, ? <: BSDVertex]]` is therefore a *braid word*: a
sequence of typed generators where each element knows its source and target strands.
Evaluating such a list one element at a time is the JVM analogue of reading a braid
word left-to-right and applying each generator.

The difficulty is that `BSDArrow` carries no `unapply`, so constructor-style matching
is rejected by the compiler:

```scala
// ILLEGAL — BSDArrow has no unapply; Src and Tgt are existential
case BSDArrow(src, tgt, FVRole.Frobenius, _) => ...
```

The fix is a **braid pop**: instead of destructuring the whole constructor, pop only
the field you need and let the declared bound resolve the existential:

```scala
// Pop .role — a concrete FVRole with no existential involved.
// .src and .tgt carry bound ? <: BSDVertex, accepted where BSDVertex is expected.
a.role match
  case FVRole.Frobenius    => ThresholdConstraint.FrobeniusGE(a.src, a.tgt)
  case FVRole.Verschiebung => ThresholdConstraint.VerschiebungLE(a.src, a.tgt)

// Alternatively, pin the concrete case with a type-test (fully resolves the existential):
case a: BSDArrow.TensorBang => ThresholdConstraint.FrobeniusGE(a.src, a.tgt)
//  a.src: Leech.type <: BSDVertex  ✓  — no cast, no ascription needed
```

The type-test form is used when exhaustiveness over the sealed enum is required (e.g.
`BSDQuiverManager.executeArrow`); the `.role match` form is used when the role
partition is the semantically meaningful one (e.g. `ThresholdProblem.fromArrows`).
`berryPhaseAngle` on `PhantomCarabiner` plays the same role at runtime: it is the
only observable that survives orbit-averaging and erases the phantom type parameters,
just as `.role` is the only field that matters for the threshold encoding.

### Dhall as an interactive input interface

Because Dhall is a *total* functional language — every expression provably terminates — its REPL can safely serve as a live configuration interface.
`batch-bridge` uses `BatchJob.dhall` as a schema where each batch step embeds a stack machine program (`List StackInstr`) directly in the configuration.
The interactive flow is:

```
dhall repl
  :load BatchJob.dhall        -- load the job definition
  :let myStep = { ... }       -- modify or extend steps on the fly
```

`dhall-to-json` then serialises the result and circe decodes it into a typed `BatchJobDef` on the Scala side.
This means the *shape* of the job is checked by Dhall's type system before any JVM code runs.

### Stack machine as an input device

The BSD Quiver stack machine (`ProgramEval.exec`) acts as the *input device* for each Spring Batch step.
A step's `input_prog` — a sequence of `StackInstr` values declared in Dhall — is evaluated by the stack machine at execution time, and the result is stored in the Spring Batch `JobExecutionContext` for downstream steps to read.
This connects the algebraic IR in `core` directly to the batch execution layer without any intermediate serialisation format.

### Execution control as an algebraic data type

Rather than encoding conditional step execution as runtime booleans, `batch-bridge` models JCL-style `COND` statements as an ADT:

```
CondExpr = Compare(threshold, op)   -- COND=(4,LT): skip if 4 < prevRC
         | Even                     -- COND=EVEN:   always execute
         | Only                     -- COND=ONLY:   execute only after ABEND
```

This ADT is declared in `BatchJob.dhall` and decoded into Scala via circe.
Invalid execution orders — such as a recovery step marked `Only` running when no step has ABENDed — are caught by `CondEvaluator` before the step actor ever starts.

### Fault isolation inspired by z/OS JES2

`JobSupervisorActor` manages a tree of `StepExecutorActor` instances under a Pekko `OneForOneStrategy`.
When a step throws `StepAbended`, only that actor is stopped; all sibling steps continue unaffected.
This design takes inspiration from z/OS JES2-style step-level fault isolation, where an individual step ABEND does not cascade into a full job failure unless the subsequent COND logic dictates it.

## Why two solvers?

Z3 and Yices 2 run as independent verification lanes over the same canonical `ThresholdProblem` AST. Agreement is a stronger signal than a single SAT verdict; each solver produces a *proof artifact* that can be checked independently — useful once the project gains SPARK/Ada packages that need external witnesses.

The two bridges are deliberately kept at arm's length: `z3-bridge` links against the Z3 JNI library; `yices-bridge` shells out to `yices-smt2`. Neither knows the other exists.

## Requirements

- JDK 17 or later
- sbt 1.x
- `yices-smt2` on `$PATH` for Yices smoke tests and CI
- `dhall-to-json` on `$PATH` for `dhall-bridge` and `batch-bridge` evaluation

## Build

```bash
sbt compile
```

To run the threshold tests and exercise both solver bridges:

```bash
sbt "core/testOnly io.siunertaq.threshold.*" "z3Bridge/compile" "yicesBridge/test"
```

To compile and verify the batch pipeline (no external tools required):

```bash
sbt "dhallBridge/testOnly io.siunertaq.batch.CondEvaluatorSpec" "batchBridge/compile"
```

For the full Yices integration smoke suite locally:

```bash
RUN_YICES_SMOKE=1 sbt "yicesBridge/test"
```

## Repository layout

```
modules/
  core/
    src/main/scala/io/siunertaq/
      BSDQuiver.scala         — BSDArrow (phantom-typed quiver), BSDVertex, FVRole, DP state, BSDQuiverManager
      MachineConstants.scala  — galoisHeight (log), octadHeight (linear), CarabinerHeight typeclass,
                                machineEpsilonReal, valuationDepth, arikiKoike{N,R}, M₂₄ rigid triple
      carabiner/              — GolayWeight extensions, SpaceTag, Carabiner, Route, golayRoute,
                                ComplexWeight (opaque), PhantomCarabiner, PhantomCarabinerRef (opaque)
      yangbaxter/             — SpiralRotation, spiralToSpectralParam, spectralRMatrix, BraidWord
      expr/                   — arithmetic ADTs, a stack-machine lowering, evaluator, and S-expression codec
      threshold/              — constraint AST, canonical S-expression support, solver input generation
  z3-bridge/          — Z3-backed Banach constraint verification (JNI)
  yices-bridge/       — Yices 2 cross-check lane (subprocess) + smoke tests
  dhall-bridge/
    src/main/resources/
      BatchJob.dhall  — Dhall schema: JCL COND ADT + stack machine input programs
    src/main/scala/io/siunertaq/
      dhall/          — DhallEffectRegistry: Dhall → IO effect registration
      batch/          — BatchJobDef, DhallBatchRegistry, CondEvaluator
  batch-bridge/       — Spring Batch + Pekko JES2; StackMachineTasklet, StepExecutorActor, JobSupervisorActor
  mlir-bridge/        — planned MLIR / Affine Dialect integration
.github/workflows/
  yices_test_ci.yml   — CI: installs Yices 2, runs threshold + solver + CondEvaluator tests
  release.yml         — Release: tag push → full test → sbt package → GitHub Release
```

## Current status

- Arithmetic IR and threshold AST implemented; all core tests passing.
- Yices and Z3 verification lanes separated and independently runnable.
- GitHub Actions CI validates the Yices path on `ubuntu-latest` with the real solver binary.
- Codebase prepared for SPARK/Ada proof packages: `lower` and S-expression round-trips already have ScalaTest specs that serve as ground-truth witnesses.
- `batch-bridge` implemented: `BatchJob.dhall` → `BatchJobDef` → Spring Batch step execution driven by the BSD Quiver stack machine, with JCL-inspired `CondExpr` execution control and Pekko `OneForOneStrategy` fault isolation.
- `CondEvaluatorSpec` covers all six `CondOp` variants with boundary values; runs in CI without any external tools.
- Automated release workflow publishes per-module JARs to GitHub Releases on version tag push.
- `carabiner/` package formalises the Golay lattice (five weight classes, self-dual route,
  M₂₄ 8A orbit match) and lifts it to a complex Berkovich evaluation layer via `PhantomCarabiner`.
- `opaque type ComplexWeight` and `opaque type PhantomCarabinerRef` hide implementation details
  behind algebra-preserving APIs; `berryPhaseAngle` and `weightNormSq` are the only exposed
  gauge-invariant observables.
- `MachineConstants` separates the logarithmic `galoisHeight` (GIT semistability mask) from
  the linear `octadHeight` (Berkovich tree position) and grounds the tower in IEEE 754 via
  `machineEpsilonReal = 2⁻⁵²` and `valuationDepth = 52`.
- `YangBaxterBanach` supplies the Satake spectral parameter bridge (`SpiralRotation →
  spiralToSpectralParam → PhantomCarabiner`) and the rational GL₂ R-matrix.
- Type-safe braid pop pattern documented and applied consistently: `List[BSDArrow[? <: BSDVertex,
  ? <: BSDVertex]]` with `.role match` or type-test resolves existential type parameters without
  casts; `BSDArrow` companion object provides lowercase smart constructors for test code.

## Future work

- SPARK/Ada proof packages for lowering preservation (`exec(lower(e)) == eval(e)`) and S-expression round-trips
- Richer IR operations and deeper Dhall REPL integration — live step modification without restarting the JVM
- Parallel step dispatch in `JobSupervisorActor` for steps sharing the same `priority` value
- Yices-verified norm thresholds wired directly into `StepExecutorActor` pre-conditions, so a step that would violate a BSD norm bound is rejected before Spring Batch ever runs it
- `mlir-bridge`: map `BSDArrow` decompositions to MLIR Affine Dialect norm constraints, JIT via LLVM IR
- Wire `PhantomCarabinerRef` into `BSDQuiverManager`: each vertex `Ref` stores a `PhantomCarabiner`
  so that `berryPhaseAngle` is tracked across arrow firings and exposed as a diagnostic observable
- `complement_theta_link_comm` remains an open theorem in `PhantomCarabiner.lean` (marked `sorry`);
  a Lean 4 proof or a Scala counterexample would close the question
- `YangBaxterBanach.yang_baxter_eq` is similarly `sorry`; connecting it to the spectral R-matrix
  via the automorphic L-function trace (§9 of the Lean spec) is the next algebraic milestone
- Replace the `galoisHeight` mock in `BSDQuiver.scala` (`tau.toDouble`) with `MachineConstants.galoisHeight`
  once the DP accumulation semantics are settled; currently kept separate to avoid changing
  the DP weight scale without an accompanying test update
- `CarabinerCodeword` and `GoppaInterface` stubs in `Carabiner.scala` require Steiner S(5,8,24)
  orbit-boundary data to complete
- `golayWeightToBraid` bridge between `GolayWeight` and `BraidWord` (sketched in `YangBaxterBanach.lean`)
  needs a concrete Ariki-Koike specialisation at `n=8, r=3`

## Contributing

1. Open an issue before larger design changes.
2. Branch off `feature/Refinement_dhall` or the relevant feature branch.
3. Keep changes focused; prefer lawful additions over convenient ones.
4. `sbt test` must be green before opening a PR.

Contributions that improve proof coverage, solver reliability, or documentation are especially welcome.