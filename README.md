# Siunertaq

![Yices Threshold CI](https://github.com/Yoshyhyrro/Siunertaq/actions/workflows/yices_test_ci.yml/badge.svg)

*ᓯᐅᓇᕐᑕᖅ — Inuktitut for "that which lies ahead; a purpose"*

A Scala 3 project for formalising Banach quiver threshold verification and a small arithmetic IR proof pipeline, designed to stay honest about effects and never lie to the type system.

The repository combines:
- a `core` module for build-graph primitives, a minimal arithmetic IR, and a GADT-based typed expression layer,
- a `z3-bridge` module for Z3-based Banach constraint verification,
- a `yices-bridge` module for Yices 2 SMT cross-checking,
- a `dhall-bridge` module for total Dhall input evaluation via `cats-effect`,
- a planned `mlir-bridge` module for future MLIR integration.

## The idea

Build systems are directed graphs. So are Banach spaces with norm-compatible maps. Siunertaq exploits this overlap by modelling build dependencies as arrows in a *BSD quiver* — a directed graph whose vertices carry norm bounds and whose arrows are typed by their role:

- **Frobenius arrows** (`FVRole.Frobenius`) point in the *norm-increasing* direction: forward dependencies, compilation steps, things that must happen.
- **Verschiebung arrows** (`FVRole.Verschiebung`) point in the *norm-decreasing* direction: backward dependencies, cache invalidation, things that propagate failure upstream.

Each `BSDArrow` carries an `IO[Unit]` effect that only fires after an SMT solver confirms the norm constraints are satisfiable. The Dieudonné relation (`selmer.norm × affine.norm = p × leech.norm`) acts as a global coherence condition tying the four standard vertices together.

The goal is to make ill-ordered builds *unrepresentable* rather than merely *ill-advised*.

## Typed expressions — `TypedExpr[T]`

The arithmetic IR ships two layers:

| Layer | Type | Purpose |
|---|---|---|
| `Expr` (untyped) | ADT | Used by `ExprTyping`, `ExprEval`, `Lowering`, `SExpr` |
| `TypedExpr[T <: Ty]` (GADT) | Indexed type | Compile-time safety; eliminates runtime `ExprTyping.typeOf` checks |

`TypedExpr` uses Scala 3 GADTs to encode type-index constraints directly:

- `TAdd[T]` — homogeneous addition for any `T <: Ty` (Scalar + Scalar or Vec3 + Vec3, never mixed)
- `TMul` — `Scalar × Scalar → Scalar` only; Vec3 multiplication is a compile error
- `TDot` — `Vec3 × Vec3 → Scalar` only; the result type is fixed at the constructor

Because `Ty.Scalar` and `Ty.Vec3` are parameterless enum cases in Scala 3, their singleton types (`Ty.Scalar.type`, `Ty.Vec3.type`) serve as the index. A `TDot` node can never carry a `Vec3`-typed result without a compile error — no runtime check required.

## Why two solvers?

Z3 and Yices 2 run as independent verification lanes over the same canonical `ThresholdProblem` AST. Agreement is a stronger signal than a single SAT verdict; each solver produces a *proof artifact* that can be checked independently — useful once the project gains SPARK/Ada packages that need external witnesses.

The two bridges are deliberately kept at arm's length: `z3-bridge` links against the Z3 JNI library; `yices-bridge` shells out to `yices-smt2`. Neither knows the other exists.

The `feature/Dependently-typed` branch strengthens the Yices 2 SMT encoding so that the dependent-type index carried by each `TypedExpr[T]` node flows through the constraint pipeline, keeping solver witnesses structurally aligned with typed IR nodes.

## Requirements

- JDK 17 or later
- sbt 1.x
- `yices-smt2` on `$PATH` for Yices smoke tests and CI
- `dhall-to-json` (optional) for `dhall-bridge` evaluation

## Build

```bash
sbt compile
```

To run the threshold tests and exercise both solver bridges:

```bash
sbt "core/testOnly io.siunertaq.threshold.*" "z3Bridge/compile" "yicesBridge/test"
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
      expr/           — untyped arithmetic ADTs (Expr, Instr, Program, MachineStack),
                        GADT-based TypedExpr[T <: Ty] for compile-time type safety,
                        stack-machine lowering, evaluator, and S-expression codec
      threshold/      — constraint AST, canonical S-expression support, solver input generation
  z3-bridge/          — Z3-backed Banach constraint verification (JNI)
  yices-bridge/       — Yices 2 cross-check lane (subprocess) + smoke tests;
                        feature/Dependently-typed strengthens dependent-type index propagation
  dhall-bridge/       — total Dhall evaluation → circe decode → IO effect registration
  mlir-bridge/        — planned MLIR / Affine Dialect integration
.github/workflows/
  yices_test_ci.yml   — CI: installs Yices 2 from ppa:sri-csl/formal-methods, runs threshold + solver tests
```

## Current status

- Arithmetic IR and threshold AST implemented; all core tests passing.
- `TypedExpr[T <: Ty]` GADT layer implemented: compile-time type safety for scalar/vec3 arithmetic, eliminating runtime `ExprTyping.typeOf` checks.
- `LoweringSpec` updated to cover typed expression nodes and extended preservation proofs.
- Dependent-type index propagation in the Yices 2 bridge in progress on `feature/Dependently-typed`.
- Yices and Z3 verification lanes remain separated and independently runnable.
- GitHub Actions CI validates the Yices path on `ubuntu-latest` with the real solver binary.
- Codebase prepared for SPARK/Ada proof packages: `lower` and S-expression round-trips already have ScalaTest specs that serve as ground-truth witnesses.

## Future work

- SPARK/Ada proof packages for lowering preservation (`exec(lower(e)) == eval(e)`) and S-expression round-trips
- Typed lowering: `TypedExpr[T] → Program` that carries the GADT index through to the stack-machine encoding
- Richer IR operations and Dhall command-source integration
- `mlir-bridge`: map `BSDArrow` decompositions to MLIR Affine Dialect norm constraints, JIT via LLVM IR

## Contributing

1. Open an issue before larger design changes.
2. Branch off `feature/Dependently-typed` (current focus) or the relevant feature branch.
3. Keep changes focused; prefer lawful additions over convenient ones.
4. `sbt test` must be green before opening a PR.

Contributions that improve proof coverage, solver reliability, or documentation are especially welcome.
