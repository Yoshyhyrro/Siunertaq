package io.siunertaq.dhall

import cats.effect.IO
import cats.syntax.either.*
import io.circe.parser.decode
import io.circe.generic.auto.*
// fs2.io.process removed because it is not used

/** Registry that registers Dhall REPL return values as IO effects.
  *
  * Dhall is a total (Turing-incomplete) functional language, so evaluation always terminates.
  * Leveraging this property, the output of the `dhall-to-json` subprocess can be
  * safely registered ahead of time as IO effects.
  *
  * Execution flow:
  *   Dhall expression (list of BanachThresholds in *.dhall)
  *     → dhall-to-json (subprocess)
  *     → JSON → circe decode → List[BanachThreshold]
  *     → Z3 verification (z3Bridge)
  *     → inject as `IO[Unit]` into `BSDArrow.effect`
  *
  * Example Dhall (thresholds.dhall):
  * {{{
  *   [ { norm_bound = 12.0, vertex = "AffineDual", effect_tag = "frobenius_compile" }
  *   , { norm_bound = 16.0, vertex = "Selmer",     effect_tag = "verschiebung_cache" }
  *   ]
  * }}}
  */
object DhallEffectRegistry:

  /** Norm threshold entry described in Dhall */
  final case class BanachThreshold(
    norm_bound:  Double,
    vertex:      String,
    effect_tag:  String
  )

  /** Resolve the path to the dhall-to-json binary.
    * Use the DHALL_TO_JSON environment variable if set.
    */
  private def dhallBin: String =
    sys.env.getOrElse("DHALL_TO_JSON", "dhall-to-json")

  /** Evaluate a Dhall expression with `dhall-to-json` and return the JSON string.
    *
    * Using fs2.io.process would allow managing the process lifecycle as a Cats Effect Resource.
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
