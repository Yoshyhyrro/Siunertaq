// =============================================================================
// build.sbt — $name$ (generated from banach-shake.g8)
//
// Stack:
//   Scala $scala_version$ (Scala 3)  ·  sbt $sbt_version$
//   Akka Streams $akka_version$ (Apache 2.0 最終版)
//   Z3 Java Bindings $z3_version$  ·  Cats Effect $cats_effect_version$
//
// [Akka ライセンス注意]
//   2.6.21 が Apache 2.0 の最終版。2.7+ は BSL。
//   商用利用で最新版が必要な場合:
//     resolvers += "Akka library repository" at "https://repo.akka.io/maven"
//     + AKKA_TOKEN 環境変数 (https://account.akka.io/token)
//
// [Z3 ネイティブライブラリ]
//   io.github.p-org.solvers:z3 は com.microsoft.z3.* パッケージをそのまま提供。
//   libz3java.so は OS パッケージ (apt install z3) または
//   Z3_LIB_PATH 環境変数で指定。
// =============================================================================

ThisBuild / scalaVersion     := "$scala_version$"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "$organization$"
ThisBuild / organizationName := "$name$"

// JNI (Z3 / MLIR) が --add-opens を必要とする場合の対策
ThisBuild / javaOptions ++= Seq(
  "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "--add-opens", "java.base/java.util=ALL-UNNAMED"
)
// javaOptions を fork 先のサブプロセスに渡す
ThisBuild / fork := true

// =============================================================================
// バージョン定数
// =============================================================================

val AkkaVersion       = "$akka_version$"
val CatsEffectVersion = "$cats_effect_version$"
val Fs2Version        = "$fs2_version$"
val CirceVersion      = "$circe_version$"
val Z3Version         = "$z3_version$"
val ScalaTestVersion  = "$scalatest_version$"
val LogbackVersion    = "$logback_version$"

// =============================================================================
// 共通コンパイラオプション (Scala 3)
// =============================================================================

val commonScalacOptions = Seq(
  "-encoding", "utf8",
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:implicitConversions",
  "-language:higherKinds",
  // Scala 3 固有: 厳格な等値比較
  "-Ycheck-all-patmat",
  // IO 効果の捨て忘れを警告 (Scala 3 では -Wvalue-discard)
  "-Wvalue-discard",
  "-Wunused:all"
)

// =============================================================================
// 共通依存ライブラリ (全モジュール共通)
// =============================================================================

val commonDependencies = Seq(
  "ch.qos.logback" % "logback-classic" % LogbackVersion,
  "org.scalatest" %% "scalatest"       % ScalaTestVersion % Test
)

// =============================================================================
// プロジェクト定義
// =============================================================================

lazy val root = (project in file("."))
  .aggregate(core, z3Bridge, dhallBridge, mlirBridge)
  .settings(
    name           := "$name$",
    publish / skip := true,  // アグリゲータは公開しない
    scalacOptions ++= commonScalacOptions
  )

// ---------------------------------------------------------------------------
// core: BSDVertex / BSDArrow クイバー型 + Shake 相当スケジューラ
//   - Akka Streams でルールグラフを Flow/Graph として表現
//   - Cats Effect IO で Dhall REPL 効果を登録
// ---------------------------------------------------------------------------
lazy val core = (project in file("modules/core"))
  .settings(
    name := "$name$-core",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      // Akka Streams: 有向グラフ (BSDArrow) を RunnableGraph にマッピング
      "com.typesafe.akka" %% "akka-stream"              % AkkaVersion cross CrossVersion.for3Use2_13,
      "com.typesafe.akka" %% "akka-actor-typed"         % AkkaVersion cross CrossVersion.for3Use2_13,
      "com.typesafe.akka" %% "akka-slf4j"               % AkkaVersion cross CrossVersion.for3Use2_13,
      // Cats Effect: IO 効果基盤
      "org.typelevel"     %% "cats-effect"              % CatsEffectVersion,
      // fs2: IO に親和性の高いストリーム層
      "co.fs2"            %% "fs2-core"                 % Fs2Version,
      "co.fs2"            %% "fs2-io"                   % Fs2Version,
      // circe: Dhall → JSON デコード
      "io.circe"          %% "circe-core"               % CirceVersion,
      "io.circe"          %% "circe-generic"            % CirceVersion,
      "io.circe"          %% "circe-parser"             % CirceVersion,
      // テスト
      "com.typesafe.akka" %% "akka-stream-testkit"      % AkkaVersion    % Test cross CrossVersion.for3Use2_13,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion    % Test cross CrossVersion.for3Use2_13,
      "org.typelevel"     %% "cats-effect-testing-scalatest" % "1.5.0"   % Test
    )
  )

// ---------------------------------------------------------------------------
// z3Bridge: バナッハノルム閾値の SMT 検証
//   import com.microsoft.z3._  がそのまま使える
//   (io.github.p-org.solvers:z3 は com.microsoft.z3.* を再公開したもの)
// ---------------------------------------------------------------------------
lazy val z3Bridge = (project in file("modules/z3-bridge"))
  .dependsOn(core)
  .settings(
    name := "$name$-z3",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      // Maven Central で入手可能な Z3 Java バインディング
      // パッケージ名: com.microsoft.z3.{Context, Solver, RealExpr, ...}
      "io.github.p-org.solvers" % "z3" % Z3Version,
      // ---- 代替: com.microsoft.z3 ローカルビルド版 ----
      // "com.microsoft" % "z3" % "4.12.6" from "file:lib/com.microsoft.z3.jar"
    ),
    // Z3 ネイティブライブラリ (libz3.so + libz3java.so)
    // apt install z3 または環境変数 Z3_LIB_PATH で上書き
    javaOptions ++= Seq(
      s"-Djava.library.path=\${sys.env.getOrElse("Z3_LIB_PATH", "$z3_lib_path$")}"
    )
  )

// ---------------------------------------------------------------------------
// dhallBridge: Dhall REPL 戻り値 → IO 効果登録
//   Dhall の全域性 (turing 不完全) を利用して REPL 評価結果を安全に効果化する。
//   dhall-to-json サブプロセス + circe でデコードする戦略を採用。
//   (JVM バインディングが薄いため subprocess 経由が現実的)
// ---------------------------------------------------------------------------
lazy val dhallBridge = (project in file("modules/dhall-bridge"))
  .dependsOn(core)
  .settings(
    name := "$name$-dhall",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      "org.typelevel" %% "cats-effect"  % CatsEffectVersion,
      "co.fs2"        %% "fs2-io"       % Fs2Version,
      "io.circe"      %% "circe-parser" % CirceVersion
      // dhall バイナリのパス: sys.env("DHALL_TO_JSON") または PATH 経由
    )
  )

// ---------------------------------------------------------------------------
// mlirBridge: S式 → MLIR Affine Dialect → Scala JNI
//   sexpr_angstrom.ml (OCaml / Angstrom) の Scala 3 移植 +
//   MLIR C API JNI ブリッジ。
//   Frobenius/Verschiebung 分解された BSDArrow を
//   mlir::AffineMap のノルム制約として埋め込む。
// ---------------------------------------------------------------------------
lazy val mlirBridge = (project in file("modules/mlir-bridge"))
  .dependsOn(core, z3Bridge)
  .settings(
    name := "$name$-mlir",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion
    ),
    // MLIR 共有ライブラリ (llvm-project ビルド成果物)
    javaOptions ++= Seq(
      s"-Djava.library.path=\${sys.env.getOrElse("MLIR_LIB_PATH", "$mlir_lib_path$")}"
    )
  )

// =============================================================================
// テスト設定
// =============================================================================

ThisBuild / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")

// =============================================================================
// fat jar アセンブリ — sbt-assembly プラグイン追加時に有効化
// =============================================================================
// assembly / mainClass := Some("$package$.Main")
// assembly / assemblyMergeStrategy := {
//   case PathList("reference.conf")    => MergeStrategy.concat
//   case PathList("META-INF", _*)      => MergeStrategy.discard
//   case _                             => MergeStrategy.first
// }
