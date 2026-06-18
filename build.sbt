// =============================================================================
// build.sbt — $name$ (generated from siunertaq.g8)
//
// "Siunertaq" (Kalarit: "that which is ahead; purpose")
// JVM build system based on directed Banach-space quivers
//
// Stack:
//   Scala $scala_version$ · sbt $sbt_version$ · JDK 17+
//   Apache Pekko Streams $pekko_version$ (Apache 2.0)
//   Z3 Java Bindings $z3_version$ · Cats Effect $cats_effect_version$
//
// [Breaking changes in Scala 3.8]
//   - JDK 17+ is required (sun.misc.Unsafe deprecation, JEP 471)
//   - The standard library is now compiled for Scala 3
//   - LTS is 3.3.x. 3.9 is planned as the next LTS (Q2 2026)
//
// [About Apache Pekko]
//   Apache 2.0 fork of Akka 2.6.x. Package names changed from
//   com.typesafe.akka → org.apache.pekko.
//   CrossVersion.for3Use2_13 is unnecessary for native Scala 3 support.
//
// [CVE-2025-12183 / lz4-java]
//   org.lz4:lz4-java is end-of-life.
//   Pekko 1.4.0 has already switched to at.yawk.lz4:lz4-java.
//   If you use lz4 directly in your code, prefer at.yawk.lz4.
//
// [Z3 native library]
//   io.github.p-org.solvers:z3 republishes the com.microsoft.z3.* packages.
//   Install libz3java.so via `apt install z3` or set via `Z3_LIB_PATH`.
// =============================================================================

ThisBuild / scalaVersion     := "3.8.3"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "io.siunertaq"
ThisBuild / organizationName := "Siunertaq"

// Scala 3.8 + JDK 17: sun.misc.Unsafe is restricted
// JVM flags may be required for the new lazy val implementation
ThisBuild / javaOptions ++= Seq(
  "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "--add-opens", "java.base/java.util=ALL-UNNAMED"
)
ThisBuild / fork := true

// =============================================================================
// Version constants
// =============================================================================

val PekkoVersion       = "1.4.0"
val CatsEffectVersion  = "3.5.1"
val Fs2Version         = "3.6.1"
val CirceVersion       = "0.14.6"
val Z3Version          = "4.8.14-v5"
val ScalaTestVersion   = "3.2.16"
val LogbackVersion     = "1.4.11"
val SpringBatchVersion = "5.1.2"
val H2Version          = "2.2.224"

// =============================================================================
// Common compiler options (Scala 3.8)
// =============================================================================

val commonScalacOptions = Seq(
  "-encoding", "utf8",
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:implicitConversions",
  "-language:higherKinds",
  // Scala 3.8: strict pattern-match checking
  "-Ycheck-all-patmat",
  // Warn about discarding IO effect values
  "-Wvalue-discard",
  "-Wunused:all",
  // Scala 3.8: SIP-67 strict-equality pattern-match support
  "-language:strictEquality"
)

// =============================================================================
// Common dependencies
// =============================================================================

val commonDependencies = Seq(
  "ch.qos.logback" %  "logback-classic" % LogbackVersion,
  "org.scalatest"  %% "scalatest"       % ScalaTestVersion % Test
)

// =============================================================================
// Project definitions
// =============================================================================

lazy val root = (project in file("."))
  .aggregate(core, z3Bridge, yicesBridge, dhallBridge, mlirBridge, batchBridge, petersenMzv, postgresBridge)
  .settings(
    name           := "Siunertaq",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions
  )

// ---------------------------------------------------------------------------
// core: BSDVertex / BSDArrow quiver types + Shake-like scheduler
//
//   Map directed graphs (BSDArrow) to RunnableGraph[Future[Done]]
//   using Apache Pekko Streams.
//   Frobenius arrows = forward dependencies (build execution)
//   Verschiebung arrows = backward dependencies (cache invalidation)
// ---------------------------------------------------------------------------
lazy val core = (project in file("modules/core"))
  .settings(
    name := "Siunertaq-core",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      // Apache Pekko Streams: org.apache.pekko (Scala 3 ネイティブ)
      "org.apache.pekko" %% "pekko-stream"              % PekkoVersion,
      "org.apache.pekko" %% "pekko-actor-typed"         % PekkoVersion,
      "org.apache.pekko" %% "pekko-slf4j"               % PekkoVersion,
      // Cats Effect: IO 効果基盤 (Dhall REPL 戻り値の効果登録)
      "org.typelevel"    %% "cats-effect"                % CatsEffectVersion,
      // fs2: Cats Effect と親和性の高いストリーム層
      "co.fs2"           %% "fs2-core"                   % Fs2Version,
      "co.fs2"           %% "fs2-io"                     % Fs2Version,
      // circe: Dhall → JSON デコード
      "io.circe"         %% "circe-core"                 % CirceVersion,
      "io.circe"         %% "circe-generic"              % CirceVersion,
      "io.circe"         %% "circe-parser"               % CirceVersion,
      // テスト
      "org.apache.pekko" %% "pekko-stream-testkit"       % PekkoVersion % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed"  % PekkoVersion % Test,
      "org.typelevel"    %% "cats-effect-testing-scalatest" % "1.5.0"   % Test
    )
  )

// ---------------------------------------------------------------------------
// z3Bridge: SMT verification of Banach-norm thresholds
//
//   `import com.microsoft.z3._` works as-is.
//   io.github.p-org.solvers:z3 republishes com.microsoft.z3.* on Maven Central.
//   Verify Frobenius/Verschiebung directional constraints and Dieudonné relations using SMT.
// ---------------------------------------------------------------------------
lazy val z3Bridge = (project in file("modules/z3-bridge"))
  .dependsOn(core)
  .settings(
    name := "Siunertaq-z3",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      // com.microsoft.z3.{Context, Solver, RealExpr, Status, ...}
      "io.github.p-org.solvers" % "z3" % Z3Version
      // ---- 代替: ローカルビルド版 (z3 --java でビルド後 publishLocal) ----
      // "com.microsoft" % "z3" % "4.12.6" from "file:lib/com.microsoft.z3.jar"
    ),
    // Path for libz3.so + libz3java.so
    // Example on WSL: `sudo apt install z3` → /usr/lib/x86_64-linux-gnu/
    // Override per-CI via the Z3_LIB_PATH environment variable
    javaOptions ++= Seq(
      s"-Djava.library.path=${sys.env.getOrElse("Z3_LIB_PATH", "") }"
    )
  )

// ---------------------------------------------------------------------------
// yicesBridge: Yices 2 cross-check for Banach-norm thresholds
//
//   Generate SMT-LIB2 from the canonical threshold AST and
//   verify SAT/UNSAT using an external `yices-smt2` process.
//   Used as a parallel verification lane for proof artifacts, not a replacement for Z3.
// ---------------------------------------------------------------------------
lazy val yicesBridge = (project in file("modules/yices-bridge"))
  .dependsOn(core)
  .settings(
    name := "Siunertaq-yices",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies
  )

// ---------------------------------------------------------------------------
// dhallBridge: Dhall REPL results → IO effect registration
//
//   Dhall is a total (Turing-incomplete) functional language, so evaluation always terminates.
//   Leverage this to safely register REPL evaluation results as IO effects ahead of time.
//   Implementation: dhall-to-json subprocess + circe decoding
//   (subprocess strategy is practical due to limited JVM Dhall bindings)
// ---------------------------------------------------------------------------
lazy val dhallBridge = (project in file("modules/dhall-bridge"))
  .dependsOn(core)
  .settings(
    name := "Siunertaq-dhall",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      "org.typelevel" %% "cats-effect"  % CatsEffectVersion,
      "co.fs2"        %% "fs2-io"       % Fs2Version,
      "io.circe"      %% "circe-parser" % CirceVersion
      // dhall-to-json バイナリ: sys.env("DHALL_TO_JSON") または PATH 経由
    )
  )

// ---------------------------------------------------------------------------
// mlirBridge: S-exprs → MLIR Affine Dialect → JNI → Scala
//
//   Scala 3 port of sexpr_angstrom.ml (OCaml / Angstrom).
//   Embed Frobenius/Verschiebung-decomposed BSDArrow as norm constraints on mlir::AffineMap
//   and JIT-optimize via LLVM IR.
//   Future support for Clojure builds is planned (shared JVM foundation).
// ---------------------------------------------------------------------------
lazy val mlirBridge = (project in file("modules/mlir-bridge"))
  .dependsOn(core, z3Bridge)
  .settings(
    name := "Siunertaq-mlir",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion
      // MLIR C API JNI ラッパーはプロジェクト固有のため手動追加
      // "io.siunertaq" %% "mlir-jni" % "0.1.0"
    ),
    // MLIR 共有ライブラリ (llvm-project ビルド成果物)
    // Ubuntu: sudo apt install llvm-18  →  /usr/lib/llvm-18/lib/
    javaOptions ++= Seq(
      s"-Djava.library.path=${sys.env.getOrElse("MLIR_LIB_PATH", "") }"
    )
  )

// ---------------------------------------------------------------------------
// batchBridge: Spring Batch + Pekko JES2 — BSDQuiver スタックマシン駆動バッチ
//
//   Dhall で記述したジョブ定義 (BatchJob.dhall) を dhall-to-json → circe で
//   BatchJobDef に変換し、Spring Batch Step をPekkoアクターでラップする。
//   JCL COND 文の代数的セマンティクスを CondEvaluator で実装。
//   OneForOneStrategy でステップ単位のABEND隔離 (JES2相当)。
// ---------------------------------------------------------------------------
lazy val batchBridge = (project in file("modules/batch-bridge"))
  .dependsOn(core, dhallBridge)
  .settings(
    name          := "Siunertaq-batch",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      // Pekko classic Actor (Supervisor Strategy / OneForOneStrategy)
      "org.apache.pekko"          %% "pekko-actor"       % PekkoVersion,
      // Spring Batch 5: Step / Tasklet / JobRepository
      "org.springframework.batch"  % "spring-batch-core" % SpringBatchVersion,
      "org.springframework"        % "spring-jdbc"        % "6.1.14",
      // in-memory JobRepository 用 H2
      "com.h2database"             % "h2"                 % H2Version % Runtime
    )
  )

// ---------------------------------------------------------------------------
// examples/petersen-mzv: MZV depth-3 solver on the Petersen graph
//
//   Demonstrates the Petersen graph as a MZV stack machine:
//   - diameter-2 invariant (P1/UNSAT) → [TOPOLOGY ???] is dead code
//   - s1=1 divergent pole (P5/UNSAT)  → [IMAGINARY ???] dispatched to ImaginaryPopperActor
//   - s2+s3 weight conservation (P2/UNSAT) across any pentagon step
//   - SMT-LIB 2 generation via scala-smtlib + z3 subprocess verification
//
//   Parallel to yicesBridge (subprocess SMT) rather than z3Bridge (Java API).
//
//   scala-smtlib: published for Scala 2.13 only as of 0.2.1.
//   CrossVersion.for3Use2_13 required until upstream publishes a Scala 3 artifact.
//   Tracked at: https://github.com/regb/scala-smtlib/issues/???
// ---------------------------------------------------------------------------
lazy val petersenMzv = (project in file("examples/petersen-mzv"))
  .dependsOn(core)
  .settings(
    name := "petersen-mzv",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      // classic Actor for ImaginaryPopperActor (transitive via core's pekko-actor-typed,
      // listed explicitly for clarity — mirrors batchBridge convention)
      "org.apache.pekko" %% "pekko-actor"       % PekkoVersion,
      // scala-smtlib: typed SMT-LIB 2 AST + RecursivePrinter
      // CrossVersion needed until a native Scala 3 artifact is published upstream
      "com.regblanc"     %  "scala-smtlib_2.13" % "0.2.1" cross CrossVersion.for3Use2_13
    )
  )

lazy val postgresBridge = (project in file("modules/postgres-bridge"))
  .dependsOn(core)
  .settings(
    name := "Siunertaq-postgres",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      "org.typelevel"  %% "cats-effect"   % CatsEffectVersion,
      "org.postgresql" % "postgresql" % "42.7.3",
      "org.tpolecat" %% "doobie-core" % "1.0.0-RC2",
      "org.tpolecat" %% "skunk-core" % "1.0.0-RC2"
    )
  )

// =============================================================================
// Test settings
// =============================================================================

ThisBuild / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")

// =============================================================================
// fat jar — enable when adding the sbt-assembly plugin
// =============================================================================
// assembly / mainClass := Some("io.siunertaq.Main")
// assembly / assemblyMergeStrategy := {
//   case PathList("reference.conf")  => MergeStrategy.concat
//   case PathList("META-INF", _*)    => MergeStrategy.discard
//   case _                           => MergeStrategy.first
// }
