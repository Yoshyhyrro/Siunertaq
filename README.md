# Siunertaq

A Scala 3 project for formalizing Banach quiver threshold verification and an arithmetic IR proof pipeline.

The repository combines:
- a `core` module for build-graph primitives and a minimal arithmetic IR,
- a `z3-bridge` module for Z3-based Banach constraint validation,
- a `yices-bridge` module for Yices 2 SMT cross-checking,
- a `dhall-bridge` module for Dhall input evaluation,
- a planned `mlir-bridge` module for future MLIR integration.

## Why this project

Siunertaq is designed to separate two concerns cleanly:
- a build-graph model based on Banach quiver semantics (`BSDArrow`, `BanachRule`), and
- a small arithmetic and threshold verification pipeline that can be verified by SMT solvers and later by SPARK/Ada.

The current focus is on a minimal Scala-side IR, canonical S-expression conversion, and solver-backed threshold verification with both Z3 and Yices.

## Requirements

- JDK 17 or later
- sbt 1.x
- `yices-smt2` for Yices smoke tests and CI
- optionally `dhall-to-json` if you want to evaluate Dhall-based inputs in the `dhall-bridge` module

## Build

From the repository root:

```bash
sbt compile
```

To run the core threshold tests and compile the verification modules:

```bash
sbt "core/testOnly io.siunertaq.threshold.*" "z3Bridge/compile" "yicesBridge/test"
```

If you want to exercise the real Yices smoke tests locally:

```bash
RUN_YICES_SMOKE=1 sbt "yicesBridge/test"
```

## Repository layout

- `modules/core` — core Scala types and arithmetic IR implementations.
  - `src/main/scala/io/siunertaq/expr` — arithmetic expression ADTs, lowering, evaluator, and S-expression conversion.
  - `src/main/scala/io/siunertaq/threshold` — threshold constraint AST, canonical S-expression support, and solver input generation.
- `modules/z3-bridge` — current Z3-powered Banach constraint verification.
- `modules/yices-bridge` — Yices 2 SMT cross-check path and smoke tests.
- `modules/dhall-bridge` — Dhall evaluation and JSON decoding path.
- `modules/mlir-bridge` — planned future work for MLIR integration.
- `.github/workflows/yices_test_ci.yml` — GitHub Actions workflow for Yices threshold CI.

## Current status

- Arithmetic IR and threshold AST are implemented in Scala.
- Yices and Z3 verification lanes are separated and can be run from the repo.
- A GitHub Actions workflow has been added to validate the Yices threshold path on `ubuntu-latest`.
- The project is prepared for further proof work and Ada/SPARK verification.

## Future work

- add SPARK/Ada proof packages for lowering preservation and S-expression round-trips,
- extend the IR with richer operations and Dhall command source integration,
- implement the planned `mlir-bridge` integration.

## Contributing

If you want to contribute:

1. open an issue first for larger design changes,
2. create a branch off `feature/Refinement_dhall` or the relevant feature branch,
3. keep changes small and focused,
4. run `sbt test` before submitting a PR.

Contributions that improve documentation, build reliability, or solver verification are especially welcome.
