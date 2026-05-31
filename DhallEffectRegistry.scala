package $package$.dhall

import cats.effect.IO
import io.circe.parser.decode
import io.circe.generic.auto.*

/** Dhall REPL 戻り値を IO 効果として登録するレジストリ。
  *
  * Dhall は Turing 不完全な全域関数言語なので評価が必ず停止する。
  * この性質を利用して、`dhall-to-json` サブプロセスの出力を
  * 安全に IO 効果として事前登録できる。
  *
  * 実行フロー:
  *   dhall式 (*.dhall)
  *     → dhall-to-json (subprocess)
  *     → JSON → circe デコード → BanachThreshold
  *     → Z3 検証 (z3Bridge)
  *     → IO[Unit] として BSDArrow に注入
  */
object DhallEffectRegistry:

  /** Dhall で記述されるノルム閾値エントリ */
  final case class BanachThreshold(
    norm_bound:  Double,
    vertex:      String,
    effect_tag:  String
  )

  /** `dhall-to-json` を subprocess で呼び出して JSON にシリアライズする。
    *
    * 環境変数 DHALL_TO_JSON が設定されていればそのパスを使用し、
    * なければ PATH 上の `dhall-to-json` を探す。
    */
  def evalDhall(dhallExpr: String): IO[String] =
    IO.blocking:
      val bin = sys.env.getOrElse("DHALL_TO_JSON", "dhall-to-json")
      val proc = Runtime.getRuntime.exec(Array(bin))
      proc.getOutputStream.write(dhallExpr.getBytes("UTF-8"))
      proc.getOutputStream.close()
      val out = scala.io.Source.fromInputStream(proc.getInputStream).mkString
      proc.waitFor()
      out

  /** Dhall 式を評価して BanachThreshold のリストとして登録する。 */
  def loadThresholds(dhallExpr: String): IO[List[BanachThreshold]] =
    evalDhall(dhallExpr).flatMap { json =>
      IO.fromEither(decode[List[BanachThreshold]](json))
    }
