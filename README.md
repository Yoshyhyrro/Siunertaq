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
      expr/           — arithmetic ADTs, a stack-machine lowering, evaluator, and S-expression codec
      threshold/      — constraint AST, canonical S-expression support, solver input generation
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

## Future work

- SPARK/Ada proof packages for lowering preservation (`exec(lower(e)) == eval(e)`) and S-expression round-trips
- Richer IR operations and deeper Dhall REPL integration — live step modification without restarting the JVM
- Parallel step dispatch in `JobSupervisorActor` for steps sharing the same `priority` value
- Yices-verified norm thresholds wired directly into `StepExecutorActor` pre-conditions, so a step that would violate a BSD norm bound is rejected before Spring Batch ever runs it
- `mlir-bridge`: map `BSDArrow` decompositions to MLIR Affine Dialect norm constraints, JIT via LLVM IR

## Contributing

1. Open an issue before larger design changes.
2. Branch off `feature/Refinement_dhall` or the relevant feature branch.
3. Keep changes focused; prefer lawful additions over convenient ones.
4. `sbt test` must be green before opening a PR.

Contributions that improve proof coverage, solver reliability, or documentation are especially welcome.
