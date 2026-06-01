package io.siunertaq.dhall

import cats.effect.IO
import cats.syntax.either.*
import io.circe.parser.decode
import io.circe.generic.auto.*
// fs2.io.process は使用しないため削除

/** Dhall REPL 戻り値を IO 効果として登録するレジストリ。
  *
  * Dhall は Turing 不完全な全域関数言語なので評価が必ず停止する。
  * この性質を利用して `dhall-to-json` サブプロセスの出力を
  * 安全に IO 効果として事前登録できる。
  *
  * 実行フロー:
  *   Dhall式 (*.dhall 内の BanachThreshold リスト)
  *     → dhall-to-json  (subprocess, fs2.io.process)
  *     → JSON → circe デコード → List[BanachThreshold]
  *     → Z3 検証 (z3Bridge)
  *     → IO[Unit] として BSDArrow.effect に注入
  *
  * Dhall 記述例 (thresholds.dhall):
  * {{{
  *   [ { norm_bound = 12.0, vertex = "AffineDual", effect_tag = "frobenius_compile" }
  *   , { norm_bound = 16.0, vertex = "Selmer",     effect_tag = "verschiebung_cache" }
  *   ]
  * }}}
  */
object DhallEffectRegistry:

  /** Dhall で記述されるノルム閾値エントリ */
  final case class BanachThreshold(
    norm_bound:  Double,
    vertex:      String,
    effect_tag:  String
  )

  /** dhall-to-json バイナリのパスを解決する。
    * 環境変数 DHALL_TO_JSON が設定されていればそれを使う。
    */
  private def dhallBin: String =
    sys.env.getOrElse("DHALL_TO_JSON", "dhall-to-json")

  /** Dhall 式を `dhall-to-json` で評価して JSON 文字列を返す。
    *
    * fs2.io.process を使うことで Cats Effect の Resource として
    * プロセスのライフサイクルを管理する。
    */
  def evalDhall(dhallExpr: String): IO[String] =
    IO.blocking:
      val proc = Runtime.getRuntime.exec(Array(dhallBin))
      proc.getOutputStream.write(dhallExpr.getBytes("UTF-8"))
      proc.getOutputStream.close()
      val json = scala.io.Source.fromInputStream(proc.getInputStream).mkString
      val exitCode = proc.waitFor()
      if exitCode != 0 then
        val err = scala.io.Source.fromInputStream(proc.getErrorStream).mkString
        throw new RuntimeException(s"dhall-to-json failed (exit $exitCode): $err")
      json

  /** Dhall 式を評価して BanachThreshold のリストとして登録する。 */
  def loadThresholds(dhallExpr: String): IO[List[BanachThreshold]] =
    evalDhall(dhallExpr)
      .flatMap { json =>
        IO.fromEither(
          decode[List[BanachThreshold]](json)
            .leftMap(e => new RuntimeException(s"JSON decode failed: ${e.getMessage}"))
        )
      }

  /** ファイルパスから Dhall 式を読み込んで閾値を返す。 */
  def loadThresholdsFromFile(path: java.nio.file.Path): IO[List[BanachThreshold]] =
    IO.blocking(java.nio.file.Files.readString(path)).flatMap(loadThresholds)
