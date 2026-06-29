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
- a `petersen-mzv` module for MZV depth-3 reduction on the Petersen graph with full SMT verification, and
- a planned `mlir-bridge` module for future MLIR integration.

## Modules

| Module | Role | Key Types |
|---|---|---|
| `core` | BSD quiver, Golay lattice, arithmetic IR, threshold AST | `BSDArrow`, `GolayWeight`, `PhantomCarabiner`, `Expr` |
| `z3-bridge` | Z3 JNI Banach constraint solver | `BanachConstraintSolver` |
| `yices-bridge` | Yices 2 SMT cross-check lane + smoke tests | `YicesThresholdSolver`, `YicesSmtLib` |
| `dhall-bridge` | Dhall → JSON → Scala batch job decoder | `BatchJobDef`, `CondEvaluator`, `DhallBatchRegistry` |
| `batch-bridge` | JCL/JES2-style Pekko + Spring Batch orchestration | `JobSupervisorActor`, `StepExecutorActor`, `StackMachineTasklet` |
| `petersen-mzv` | MZV depth-3 reduction on the Petersen phase graph | `PetersenFluidMachine`, `ImaginaryPopperActor`, `MZVMachineBean` |
| `mlir-bridge` | *(planned)* MLIR / Affine Dialect integration | — |

---

## Mathematical Foundations: MZV and the `petersen-mzv` Module

### Multiple Zeta Values — Furusho's Framework

**Multiple zeta values (MZVs)** of depth $k$ and weight $n$ are defined by the nested sum:

$$\zeta(s_1, s_2, \ldots, s_k) = \sum_{n_1 > n_2 > \cdots > n_k \geq 1} \frac{1}{n_1^{s_1} n_2^{s_2} \cdots n_k^{s_k}}, \qquad s_1 \geq 2,\ s_i \geq 1$$

Convergence requires $s_1 \geq 2$; the pole at $s_1 = 1$ is the **Fischer obstruction** that the `ImaginaryPopperActor` handles.

Hidekazu Furusho's programme establishes that MZVs are governed by two independent multiplicative structures that agree on all actual $\zeta$-values:

| Product | Definition | Origin |
|---|---|---|
| Shuffle $\shuffle$ | Interleave index sequences preserving relative order | Iterated Chen integral |
| Stuffle (quasi-shuffle) $*$ | Shuffle + allowed merges $n_i = n_j$ with addition | Series representation |
| Double shuffle | $f \shuffle g = f * g$ on $\zeta$-values | Furusho (2011); core identity |

Both products are **weight-homogeneous**: the total weight $|\mathbf{s}| = s_1 + \cdots + s_k$ is additive and preserved.

Furusho's **pentagon equation** governs MZV coherence via the Drinfeld KZ associator
$\Phi_{\rm KZ}(A,B) \in \mathbb{C}\langle\!\langle A, B \rangle\!\rangle$:

$$\Phi(t_{12},\, t_{23})\;\Phi(t_{02},\, t_{34})\;\Phi(t_{01},\, t_{13}) \;=\; \Phi(t_{23},\, t_{34})\;\Phi(t_{01},\, t_{24}) \qquad \text{(pentagon)}$$

$$e^{B\pi i}\,\Phi(A,B)\,e^{A\pi i} \;=\; e^{-A\pi i}\,\Phi(A,B)^{-1}\,e^{-B\pi i} \qquad \text{(hexagon)}$$

These determine the double shuffle Lie algebra $\mathfrak{ds}$ and, via the **5-cycle argument**, the $\mathbb{Z}/5\mathbb{Z}$ symmetry among MZV depth relations — the direct algebraic ancestor of the Petersen graph used here.

#### IKZ Regularization (Ihara–Kaneko–Zagier)

For **divergent** MZVs ($s_1 = 1$), the IKZ regularization defines a formal weight-preserving lift. Introducing the generating series:

$$Z^{\shuffle}(T) = \exp\!\left(\sum_{k \geq 2} \frac{(-1)^k \zeta(k)}{k}\, T^k\right)$$

the regularized value satisfies:

$$\zeta^{\rm reg}(1, s_2, \ldots, s_k) = [T^0]\bigl(Z^{\shuffle}(T)^{-1} \cdot \widetilde{Z}(T,\, s_2, \ldots, s_k)\bigr)$$

**Weight is preserved**: if $w = 1 + s_2 + \cdots + s_k$, then $\zeta^{\rm reg}$ has the same weight $w$.

In the code, `ImaginaryPopperActor` implements the minimal convergent lift:

$$s_1 = 1 \;\Longrightarrow\; (1,\, s_2,\, s_3) \;\longmapsto\; (2,\, s_2 - 1,\, s_3), \qquad w = 1 + s_2 + s_3 \text{ preserved}$$

### Correspondence: Furusho ↔ `petersen-mzv`

| Furusho / Standard MZV | `petersen-mzv` Code |
|---|---|
| $\zeta(s_1, s_2, s_3)$, depth-3 MZV | `MZVTriple(s1, s2, s3)` |
| Convergence: $s_1 \geq 2$ | `isConvergent: Boolean = s1 > 1` |
| Weight: $w = s_1 + s_2 + s_3$ | `mzvWeight: Int = s1 + s2 + s3` |
| Even-depth sector, real part | `Outer(phase)`, phase $\in \mathbb{Z}/5\mathbb{Z}$ |
| Odd-depth sector, Fischer obstruction | `Inner(phase)`, phase $\in \mathbb{Z}/5\mathbb{Z}$ |
| Pentagon coherence step | `applyPentagonRelation(triple, src, tgt)` |
| Shuffle step $\delta = -1$ (same sector) | `Inner↔Inner` or `Outer↔Outer` edge |
| Stuffle step $\delta = +1$ (sector crossing) | `Outer↔Inner` spoke edge |
| Diameter-2 bound (graph structure) | P1 (UNSAT): Petersen diameter $\leq 2$ |
| Weight invariance under shuffle/stuffle | P2 (UNSAT): $s_2 + s_3$ conserved $\Rightarrow w$ invariant |
| Divergent pole $s_1 = 1$ | `DivergentPoleException` — the `[IMAGINARY ???]` |
| IKZ regularization (weight-preserving lift) | `ImaginaryPopperActor`: $s_1 \to 2,\ s_2 \to s_2 - 1$ |
| Fixed-point / IKZ remainder = 0 (even weight) | P4 (SAT): `MZVTriple(3,2,1)` fixed on path `0→5→8` |
| Convergence preserved under reduction | P6 (UNSAT): $s_1 > 1$ invariant across any 2-hop path |
| Furusho's 5-cycle symmetry | $\mathbb{Z}/5\mathbb{Z}$ phase group of Petersen vertices |
| p-adic MZV (p-adic KZ equation) | `BSDVertex.Padic`, Dieudonné prime $p$ |

### Petersen Graph as $\mathbb{Z}/5\mathbb{Z}$ Phase Space

The pentagon relation acts one step at a time across the edges of the Petersen graph. The 10 vertices encode a $\mathbb{Z}/5\mathbb{Z}$ phase group across two parity sectors:

| SMT Integer | Scala Type | MZV Sector | Phase |
|---|---|---|---|
| $0,1,2,3,4$ | `Outer(i)` | Even-depth (real) | $i \in \mathbb{Z}/5\mathbb{Z}$ |
| $5,6,7,8,9$ | `Inner(i)` | Odd-depth (imaginary / Fischer) | $i \in \mathbb{Z}/5\mathbb{Z}$ |

The 15 undirected edges (30 directed arcs) split into three structurally distinct families:

| Family | Vertex Pair | Count | $\delta$ | MZV Role |
|---|---|---|---|---|
| Outer 5-cycle | $\{i,\; (i+1)\%5\}$ | 5 | $-1$ | Even-sector phase rotation (shuffle) |
| Spokes | $\{i,\; i+5\}$ | 5 | $+1$ | Real ↔ Imaginary crossing (stuffle merge) |
| Inner star $\{5/2\}$ | $\{i+5,\; ((i+2)\%5)+5\}$ | 5 | $-1$ | Odd-sector phase rotation (shuffle) |

The pentagon step transforms the triple according to:

$$\delta(e) = \begin{cases} +1 & \text{if edge } e \text{ crosses sectors (Outer} \leftrightarrow \text{Inner)} \\ -1 & \text{otherwise (same sector)} \end{cases}$$

$$(s_1,\, s_2,\, s_3) \;\xrightarrow{e}\; (s_1,\; s_2 + \delta(e),\; s_3 - \delta(e))$$

Since $s_1$ is **never modified**, and $\delta$ is added to $s_2$ and subtracted from $s_3$, we have $s_2 + s_3 = \text{const}$, hence $w = s_1 + s_2 + s_3$ is invariant — proven by P2/UNSAT.

**Example traversal** — test case `MZVTriple(3,2,1)` on path `Outer(0) → Inner(0) → Inner(3)`:

| Step | Edge | Sector jump? | $\delta$ | $(s_1, s_2, s_3)$ | $w$ |
|---|---|---|---|---|---|
| Initial | — | — | — | $(3, 2, 1)$ | $6$ |
| $0 \to 5$ | Spoke | Outer→Inner | $+1$ | $(3, 3, 0)$ | $6$ |
| $5 \to 8$ | Inner star | Inner→Inner | $-1$ | $(3, 2, 1)$ | $6$ |

The triple returns to its initial value: **fixed point** (P4/SAT). The IKZ regularization remainder is zero for this even-weight path — no odd-depth obstruction occurs.

### SMT-Verified Properties (P1–P7)

The verification suite in `mzv_petersen.smt2` / `PetersenSMTLib` encodes seven formal properties over `QF_LIA`:

| Property | Expected | Statement | Mathematical Meaning |
|---|---|---|---|
| P1 | **UNSAT** | Petersen diameter $\leq 2$ | The `[TOPOLOGY ???]` is dead code; any two phases connect in $\leq 2$ steps |
| P2 | **UNSAT** | $s_2 + s_3$ conserved per edge | Weight invariance: $w = s_1 + s_2 + s_3$ invariant under pentagon reduction |
| P3 | **SAT** | Edges $\{0,5\}$ and $\{5,8\}$ exist | Path `Outer(0)→Inner(0)→Inner(3)` is valid in the graph |
| P4 | **SAT**, model $(3,2,1)$ | `MZVTriple(3,2,1)` is a fixed point on `0→5→8` | IKZ remainder $= 0$; even weight 6 has no odd-depth obstruction |
| P5 | **UNSAT** | $s_1 = 1 \wedge s_1 > 1$ contradictory | `[IMAGINARY ???]` fires exactly at the convergence boundary |
| P6 | **UNSAT** | $s_1 > 1$ invariant across any 2-hop path | No traversal can make a convergent triple divergent |
| P7 | **SAT**, model $\text{mid}=5$ | Unique midpoint `0→?→8` | `Inner(0)` is the unique mediating phase for `Outer(0)→Inner(3)` |

### Two `???` Sites: Real vs. Imaginary

The machine contains exactly two structurally distinct `???` sites, each with its own SMT certificate:

| Site label | Exception type | SMT cert | Status | Actor |
|---|---|---|---|---|
| `[TOPOLOGY ???]` | `TopologyException` | P1 (UNSAT) | Dead code — graph diameter guarantee | Unreachable |
| `[IMAGINARY ???]` | `DivergentPoleException` | P5 (UNSAT) | Live — Fischer f9 / $s_1=1$ pole | `ImaginaryPopperActor` |

---

## The idea

Build systems are directed graphs. So are Banach spaces with norm-compatible maps. Siunertaq exploits this overlap by modelling build dependencies as arrows in a *BSD quiver* — a directed graph whose vertices carry norm bounds and whose arrows are typed by their role:

- **Frobenius arrows** (`FVRole.Frobenius`) point in the *norm-increasing* direction: forward dependencies, compilation steps, things that must happen.
- **Verschiebung arrows** (`FVRole.Verschiebung`) point in the *norm-decreasing* direction: backward dependencies, cache invalidation, things that propagate failure upstream.

Each `BSDArrow` carries an `IO[Unit]` effect that only fires after an SMT solver confirms the norm constraints are satisfiable. The **Dieudonné relation** acts as a global coherence condition tying the four standard vertices together:

$$\mathrm{norm}(\Sigma_I) \cdot \mathrm{norm}\!\left(\sqrt{A_{11}^\vee}\right) = p \cdot \mathrm{norm}(\Lambda_{24})$$

In code: `selmer.norm × affine.norm = p × leech.norm`. This mirrors the Frobenius–Verschiebung composition $F \circ V = p \cdot \mathrm{id}$ on p-divisible groups — the algebraic backbone of the BSD conjecture.

The goal is to make ill-ordered builds *unrepresentable* rather than merely *ill-advised*.

### BSD Quiver Vertices

| Vertex | Symbol | Role |
|---|---|---|
| `Leech` | $\Lambda_{24}$ | Leech lattice; norm baseline |
| `AffineDual` | $\sqrt{A_{11}^\vee}$ | Affine dual; Frobenius target |
| `Padic` | $\mathcal{O}^p$ | p-adic completion; Hida eigenvalue ratio |
| `Selmer` | $\Sigma_I$ | Selmer group; Verschiebung target |

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
| `complement` | $w \mapsto 6 - \overline{w}$ | Self-duality across critical midpoint |
| `verschiebung` | $w \mapsto w / 2$ | Witt-vector operator $V$ for $p = 2$ |
| `thetaLink` | $w \mapsto -w \cdot i$ | IUT $\Theta$-link; $-\pi/2$ rotation; $\mathbb{Z}_4$ holonomy |

Two gauge-invariant observables are exposed as read-only projections:

| Observable | Formula | Property |
|---|---|---|
| `berryPhaseAngle` | $\arg(w) \in (-\pi, \pi]$ | Advances by $-\pi/2$ per `thetaLink`; unchanged by `verschiebung` |
| `weightNormSq` | $\lvert w \rvert^2 = \mathrm{re}^2 + \mathrm{im}^2$ | Preserved by `thetaLink`; halved by `verschiebung²` |

`berryPhaseAngle` advances by exactly $-\pi/2$ under each `thetaLink` application and is
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

| Step | Weight | Orbit Size | Berkovich Height $h$ | Space Tag | Complement $h' = 8 - h$ |
|---|---|---|---|---|---|
| 0 | W0 | 1 | $0$ | Affine | $8$ (W24) |
| 1 | W8 | 759 | $8/3$ | Banach | $16/3$ (W16) |
| 2 | W12 | 2576 | $4$ | Hybrid | $4$ (W12, self-dual) |
| 3 | W16 | 759 | $16/3$ | Banach | $8/3$ (W8) |
| 4 | W24 | 1 | $8$ | Affine | $0$ (W0) |

$$\text{totalPositions} = 1 + 759 + 2576 + 759 + 1 = 4096 = 2^{12}$$

**Fan equation** (complement symmetry): $h[i] + h[4-i] = 8$ for all $i$.

The Mathieu group $M_{24}$ conjugacy class 8A has exactly 759 elements — matching
`GolayWeight.W8.orbitSize` — which is how $M_{24}$ group theory enters the route structure.

### Spectral bridge: Yang-Baxter and spiral rotations

`YangBaxterBanach` provides the Satake spectral parameter, bridging the discrete Golay
lattice to the continuous Berkovich tree:

```scala
// SpiralRotation(angle θ, scalingFactor λ ∈ ℂ)
// spiralToSpectralParam: u = λ · exp(iθ)  (Satake torus coordinate)
PhantomCarabiner.fromSpiralRotation(s)   // weight = spectral parameter
```

The spectral R-matrix $R(u)$ gives the rational $GL_2$ Yang-Baxter operator:

$$R(u)\bigl((i_1, i_2),\,(j_1, j_2)\bigr) = u\,\delta_{i_1 j_1}\delta_{i_2 j_2} + \delta_{i_1 j_2}\delta_{i_2 j_1}$$

### Height functions: two scales, two purposes

`MachineConstants` distinguishes two height functions that are frequently conflated:

| Function | Formula | Scale | Purpose |
|---|---|---|---|
| `galoisHeight(n)` | $8 \cdot \log(n) / \log(24)$ | Logarithmic | GIT semistability mask |
| `octadHeight(w)` | $w / 3$ | Linear | Berkovich tree position |

Concrete values:

| Weight | `galoisHeight` | `octadHeight` |
|---|---|---|
| $n = 8$ | $\approx 5.23$ | $8/3$ |
| $n = 12$ | $\approx 6.59$ | $4$ |
| $n = 24$ | $8.00$ | $8$ |

The logarithmic function encodes a **GIT stability condition**: orbits whose
representation dimension grows faster than $\log$ are filtered out, exactly as unstable
SIMT threads are predicated off in a warp. `machineEpsilonReal = 2^{-52}` (IEEE 754)
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
The difficulty is that `BSDArrow` carries no `unapply`, so constructor-style matching
is rejected by the compiler. The fix is a **braid pop**: instead of destructuring the whole constructor, pop only
the field you need and let the declared bound resolve the existential:

```scala
// Pop .role — a concrete FVRole with no existential involved.
a.role match
  case FVRole.Frobenius    => ThresholdConstraint.FrobeniusGE(a.src, a.tgt)
  case FVRole.Verschiebung => ThresholdConstraint.VerschiebungLE(a.src, a.tgt)
```

The type-test form is used when exhaustiveness over the sealed enum is required (e.g.
`BSDQuiverManager.executeArrow`); the `.role match` form is used when the role
partition is the semantically meaningful one (e.g. `ThresholdProblem.fromArrows`).

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

| `CondExpr` | JCL Equivalent | Semantics |
|---|---|---|
| `Compare(t, LT)` | `COND=(t,LT)` | Skip if `t < maxPrevRC` |
| `Compare(t, NE)` | `COND=(t,NE)` | Skip if `t ≠ maxPrevRC` |
| `Even` | `COND=EVEN` | Always execute (never skip) |
| `Only` | `COND=ONLY` | Execute only if a prior step ABENDed |

This ADT is declared in `BatchJob.dhall` and decoded into Scala via circe.
Invalid execution orders — such as a recovery step marked `Only` running when no step has ABENDed — are caught by `CondEvaluator` before the step actor ever starts.

### Fault isolation inspired by z/OS JES2

`JobSupervisorActor` manages a tree of `StepExecutorActor` instances under a Pekko `OneForOneStrategy`.
When a step throws `StepAbended`, only that actor is stopped; all sibling steps continue unaffected.
This design takes inspiration from z/OS JES2-style step-level fault isolation, where an individual step ABEND does not cascade into a full job failure unless the subsequent COND logic dictates it.

---

## Why two solvers?

Z3 and Yices 2 run as independent verification lanes over the same canonical `ThresholdProblem` AST. Agreement is a stronger signal than a single SAT verdict; each solver produces a *proof artifact* that can be checked independently — useful once the project gains SPARK/Ada packages that need external witnesses.

The two bridges are deliberately kept at arm's length: `z3-bridge` links against the Z3 JNI library; `yices-bridge` shells out to `yices-smt2`. Neither knows the other exists.

---

## Requirements

- JDK 17 or later
- sbt 1.x
- `yices-smt2` on `$PATH` for Yices smoke tests and CI
- `dhall-to-json` on `$PATH` for `dhall-bridge` and `batch-bridge` evaluation
- `z3` on `$PATH` (or via `Z3_PATH`) for optional MZV SMT smoke tests (`RUN_MZV_SMT_SMOKE=1`)

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

For the MZV / Petersen SMT smoke suite (requires `z3`):

```bash
RUN_MZV_SMT_SMOKE=1 sbt "petersenMzv/test"
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
examples/
  petersen-mzv/
    src/main/scala/io/siunertaq/mzv/
      domain/         — MZVTypes.scala: Vertex, MZVTriple
      machine/        — PetersenFluidMachine.scala, exception taxonomy
      enterprise/     — ImaginaryPopperActor.scala, MZVMachineBean.scala, Spring @Configuration
      smt/            — PetersenSMTLib.scala (typed SMT-LIB 2 AST), PetersenSmtSolver.scala
    src/main/resources/
      mzv_petersen.smt2  — Hand-written SMT-LIB 2 (P1–P7), runnable with z3 directly
    src/test/scala/io/siunertaq/mzv/
      PetersenSMTLibSpec.scala  — unit + smoke tests for the SMT generator
  mzv_enterprise_carabiner_system.scala  — standalone demonstration
.github/workflows/
  yices_test_ci.yml   — CI: installs Yices 2, runs threshold + solver + CondEvaluator tests
  release.yml         — Release: tag push → full test → sbt package → GitHub Release
```

---

## Release Notes

See the [GitHub Releases](../../releases) page for the full changelog.
The most recent release is **[v0.1.0-beta.3](../../releases/tag/v0.1.0-beta.3)** —
shared StackInstr JSON, `Siunertaq::StackMachine.pm`, and full `PerlBridgeSpec` green (32/32).

## Current status

- Arithmetic IR and threshold AST implemented; all core tests passing.
- Yices and Z3 verification lanes separated and independently runnable.
- GitHub Actions CI validates the Yices path on `ubuntu-latest` with the real solver binary.
- Codebase prepared for SPARK/Ada proof packages: `lower` and S-expression round-trips already have ScalaTest specs that serve as ground-truth witnesses.
- `batch-bridge` compiles cleanly under Scala 3.8.3 with `-language:strictEquality`: `BatchJob.dhall` → `BatchJobDef` → Spring Batch step execution driven by the BSD Quiver stack machine, with JCL-inspired `CondExpr` execution control and Pekko `OneForOneStrategy` fault isolation.
- `PerlBridge` provides opt-in Scala/Perl differential testing (`RUN_PERL_CROSSCHECK=1`). Generated `.pl` scripts delegate all stack-machine logic to `Siunertaq::StackMachine.pm` via `JSON::PP` — the same `{"PushScalar":{"n":5}}` format emitted by `ClassASTBridge` and stored in the PostgreSQL `instructions` column. `PerlBridgeSpec` runs 32 tests green in CI, including a full subprocess integration suite.
- `Program.toJson` in `core` is the canonical StackInstr JSON format shared by `.class` analysis (`ClassASTBridge`), PostgreSQL JSONB (`ForthRegistrar`), and Perl execution (`Siunertaq::StackMachine->execute_json`).
- `CondEvaluatorSpec` covers all six `CondOp` variants with boundary values; runs in CI without any external tools.
- Automated release workflow publishes per-module JARs to GitHub Releases on version tag push.
- `petersen-mzv` compiles cleanly: `PetersenFluidMachine` implements Furusho's pentagon coherence as a typed Scala 3 Cats Effect pipeline; `ImaginaryPopperActor` handles IKZ-style regularization of divergent triples ($s_1 = 1$); full SMT suite P1–P7 passes on `z3`.
- `postgres-bridge`: ClickHouse 24.x analytics backend operational; CDC pipeline (`mzv_triple_stream`, `bytecode_instructions`, `forth_words`) ready for production ingestion.
- `carabiner/` package formalises the Golay lattice (five weight classes, self-dual route, M₂₄ 8A orbit match) and lifts it to a complex Berkovich evaluation layer via `PhantomCarabiner`.
- `opaque type ComplexWeight` and `opaque type PhantomCarabinerRef` hide implementation details behind algebra-preserving APIs; `berryPhaseAngle` and `weightNormSq` are the only exposed gauge-invariant observables.
- `MachineConstants` separates the logarithmic `galoisHeight` (GIT semistability mask) from the linear `octadHeight` (Berkovich tree position) and grounds the tower in IEEE 754 via `machineEpsilonReal = 2⁻⁵²` and `valuationDepth = 52`.
- `YangBaxterBanach` supplies the Satake spectral parameter bridge (`SpiralRotation → spiralToSpectralParam → PhantomCarabiner`) and the rational GL₂ R-matrix.
- v0.1.0-beta.3: all five active modules (`core`, `z3Bridge`, `yicesBridge`, `batchBridge`, `petersenMzv`) compile cleanly; `PerlBridgeSpec` 32/32 green including Perl subprocess integration.

## Future work

- Richer IR operations and deeper Dhall REPL integration — live step modification without restarting the JVM
- Parallel step dispatch in `JobSupervisorActor` for steps sharing the same `priority` value
- Yices-verified norm thresholds wired directly into `StepExecutorActor` pre-conditions, so a step that would violate a BSD norm bound is rejected before Spring Batch ever runs it
- `mlir-bridge`: map `BSDArrow` decompositions to MLIR Affine Dialect norm constraints, JIT via LLVM IR
- Language bridge expansion: extend the `PerlBridge` pattern to **Portable Ruby** (mruby or CRuby `--with-static-stdlib`) as the next cross-validation target; unify `PerlBackend` and `RubyBackend` under a `ScriptBackend` trait so `toPerlScript` / `toRubyScript` share one generator
- ClickHouse: additional materialised views for per-step latency distribution and cross-language divergence tracking (cases where `MISMATCH` was logged)
- `postgres-bridge`: wire `mzv_triple_log` PostgreSQL audit table directly into the Pekko supervision tree so every `ImaginaryPopperActor` regularization event is immutably recorded (ClickHouse CDC mirror `mzv_triple_stream` is complete; direct actor-tree wiring is pending)
- `batchBridge` residual warnings: `ActorProtocol.scala` (`ActorRef` unused import), `JobSupervisorActor.scala` (`Terminated(_)` pattern variable), `MZVMachineBean.scala` (`unsafeRunSync`/`unsafeToFuture` → `using`), `PetersenSmtSolver.scala` (`Files.writeString` return discarded)

## Contributing

1. Open an issue before larger design changes.
2. Branch off `example/jvm-mzv` or the relevant feature branch.
3. Keep changes focused; prefer lawful additions over convenient ones.
4. `sbt test` must be green before opening a PR.

Contributions that improve proof coverage, solver reliability, or documentation are especially welcome.