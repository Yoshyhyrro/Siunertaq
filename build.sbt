// =============================================================================
// build.sbt — $name$ (generated from siunertaq.g8)
//
// "Siunertaq" (カラーリット語: 前方にあるもの・目的)
// 方向付きバナッハ空間クイバーを使った JVM ビルドシステム
//
// Stack:
//   Scala $scala_version$  ·  sbt $sbt_version$  ·  JDK 17+
//   Apache Pekko Streams $pekko_version$ (Apache 2.0)
//   Z3 Java Bindings $z3_version$  ·  Cats Effect $cats_effect_version$
//
// [Scala 3.8 の破壊的変更]
//   - JDK 17 以上が必須 (sun.misc.Unsafe 廃止対応、JEP 471)
//   - 標準ライブラリが Scala 3 でコンパイルされるようになった
//   - LTS は 3.3.x。3.9 が次期 LTS 予定 (Q2 2026)
//
// [Apache Pekko について]
//   Akka 2.6.x の Apache 2.0 フォーク。パッケージ名が
//   com.typesafe.akka → org.apache.pekko に変わっている。
//   Scala 3 ネイティブ対応のため CrossVersion.for3Use2_13 は不要。
//
// [CVE-2025-12183 / lz4-java]
//   org.lz4:lz4-java はメンテナンス終了。
//   Pekko 1.4.0 はすでに at.yawk.lz4:lz4-java に切り替え済み。
//   自前コードで lz4 を直接使う場合も at.yawk.lz4 を使うこと。
//
// [Z3 ネイティブライブラリ]
//   io.github.p-org.solvers:z3 は com.microsoft.z3.* パッケージをそのまま提供。
//   libz3java.so は apt install z3 または Z3_LIB_PATH 環境変数で指定。
// =============================================================================

ThisBuild / scalaVersion     := "3.8.3"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "io.siunertaq"
ThisBuild / organizationName := "Siunertaq"

// Scala 3.8 + JDK 17: sun.misc.Unsafe が制限されるため
// lazy val の新実装に対応した JVM フラグが必要になる場合がある
ThisBuild / javaOptions ++= Seq(
  "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "--add-opens", "java.base/java.util=ALL-UNNAMED"
)
ThisBuild / fork := true

// =============================================================================
// バージョン定数
// =============================================================================

val PekkoVersion       = "1.4.0"
val CatsEffectVersion  = "3.5.1"
val Fs2Version         = "3.6.1"
val CirceVersion       = "0.14.6"
val Z3Version          = "4.8.14-v5"
val ScalaTestVersion   = "3.2.16"
val LogbackVersion     = "1.4.11"

// =============================================================================
// 共通コンパイラオプション (Scala 3.8)
// =============================================================================

val commonScalacOptions = Seq(
  "-encoding", "utf8",
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:implicitConversions",
  "-language:higherKinds",
  // Scala 3.8: 厳格パターンマッチ検査
  "-Ycheck-all-patmat",
  // IO 効果の捨て忘れを警告
  "-Wvalue-discard",
  "-Wunused:all",
  // Scala 3.8: SIP-67 厳格等値のパターンマッチ対応
  "-language:strictEquality"
)

// =============================================================================
// 共通依存ライブラリ
// =============================================================================

val commonDependencies = Seq(
  "ch.qos.logback" %  "logback-classic" % LogbackVersion,
  "org.scalatest"  %% "scalatest"       % ScalaTestVersion % Test
)

// =============================================================================
// プロジェクト定義
// =============================================================================

lazy val root = (project in file("."))
  .aggregate(core, z3Bridge, yicesBridge, dhallBridge, mlirBridge)
  .settings(
    name           := "Siunertaq",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions
  )

// ---------------------------------------------------------------------------
// core: BSDVertex / BSDArrow クイバー型 + Shake 相当スケジューラ
//
//   Apache Pekko Streams で有向グラフ (BSDArrow) を
//   RunnableGraph[Future[Done]] にマッピングする。
//   Frobenius 矢印 = 前向き依存 (ビルド実行)
//   Verschiebung 矢印 = 後向き依存 (キャッシュ無効化)
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
// z3Bridge: バナッハノルム閾値の SMT 検証
//
//   import com.microsoft.z3._  がそのまま使える。
//   io.github.p-org.solvers:z3 は com.microsoft.z3.* を Maven Central で再公開。
//   Frobenius/Verschiebung 方向制約 + Dieudonné 関係を SMT で検証。
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
    // libz3.so + libz3java.so のパス
    // 今はwsl:  sudo apt install z3  →  /usr/lib/x86_64-linux-gnu/
    // 環境変数 Z3_LIB_PATH で CI ごとに上書き可能
    javaOptions ++= Seq(
      s"-Djava.library.path=${sys.env.getOrElse("Z3_LIB_PATH", "") }"
    )
  )

// ---------------------------------------------------------------------------
// yicesBridge: バナッハノルム閾値の Yices 2 クロスチェック
//
//   canonical threshold AST から SMT-LIB2 を生成し、
//   外部 `yices-smt2` プロセスで SAT/UNSAT を確認する。
//   Z3 の置き換えではなく、proof artifact 用の並列検証レーンとして使う。
// ---------------------------------------------------------------------------
lazy val yicesBridge = (project in file("modules/yices-bridge"))
  .dependsOn(core)
  .settings(
    name := "Siunertaq-yices",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies
  )

// ---------------------------------------------------------------------------
// dhallBridge: Dhall REPL 戻り値 → IO 効果登録
//
//   Dhall は Turing 不完全な全域関数言語なので評価が必ず停止する。
//   この性質を利用して REPL 評価結果を安全に IO 効果として事前登録できる。
//   実装戦略: dhall-to-json サブプロセス + circe デコード
//   (JVM 上の Dhall バインディングが薄いため subprocess 戦略が現実的)
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
// mlirBridge: S式 → MLIR Affine Dialect → JNI → Scala
//
//   sexpr_angstrom.ml (OCaml / Angstrom) の Scala 3 移植。
//   Frobenius/Verschiebung 分解された BSDArrow を
//   mlir::AffineMap のノルム制約として埋め込み、
//   LLVM IR を経由して JIT 最適化する。
//   将来的に Clojure ビルドへの対応も想定 (JVM 共通基盤)。
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

// =============================================================================
// テスト設定
// =============================================================================

ThisBuild / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")

// =============================================================================
// fat jar — sbt-assembly プラグイン追加時に有効化
// =============================================================================
// assembly / mainClass := Some("io.siunertaq.Main")
// assembly / assemblyMergeStrategy := {
//   case PathList("reference.conf")  => MergeStrategy.concat
//   case PathList("META-INF", _*)    => MergeStrategy.discard
//   case _                           => MergeStrategy.first
// }
