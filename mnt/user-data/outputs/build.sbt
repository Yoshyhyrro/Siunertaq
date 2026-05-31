// =============================================================================
// build.sbt — banach-shake.g8 テンプレートリポジトリ自体のビルド定義
//
// このファイルは giter8 テンプレートのテスト用。
// 実際に生成されるプロジェクトの build.sbt は
//   src/main/g8/build.sbt
// にある。
//
// テスト方法:
//   sbt g8        -- デフォルトパラメータでテンプレートを展開
//   sbt g8Test    -- 展開後プロジェクトを compile + test
// =============================================================================

lazy val root = (project in file("."))
  .enablePlugins(ScriptedPlugin)
  .settings(
    name        := "banach-shake.g8",
    // giter8 プラグインのテスト設定
    Test / test := {
      val _ = (Test / g8Test).toTask("").value
    },
    scriptedLaunchOpts ++= Seq(
      "-Xms512m", "-Xmx1g",
      "-XX:ReservedCodeCacheSize=128m",
      "-Xss2m",
      "-Dfile.encoding=UTF-8"
    )
  )
