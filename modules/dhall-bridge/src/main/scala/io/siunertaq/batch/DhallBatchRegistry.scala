package io.siunertaq.batch

import cats.effect.IO
import io.circe.parser.decode

/** DhallEffectRegistry と同じパターンで BatchJobDef を読み込む。
 *
 *  Dhall は全域言語なので評価は必ず停止する。
 *  dhall-to-json サブプロセス → JSON → circe decode → BatchJobDef
 */
object DhallBatchRegistry:

  private def dhallBin: String =
    sys.env.getOrElse("DHALL_TO_JSON", "dhall-to-json")

  def evalDhall(dhallExpr: String): IO[String] =
    IO.blocking:
      val proc = Runtime.getRuntime.exec(Array(dhallBin))
      proc.getOutputStream.write(dhallExpr.getBytes("UTF-8"))
      proc.getOutputStream.close()
      val json     = scala.io.Source.fromInputStream(proc.getInputStream).mkString
      val exitCode = proc.waitFor()
      if exitCode != 0 then
        val err = scala.io.Source.fromInputStream(proc.getErrorStream).mkString
        throw RuntimeException(s"dhall-to-json failed (exit $exitCode): $err")
      json

  def loadBatchJob(dhallExpr: String): IO[BatchJobDef] =
    evalDhall(dhallExpr).flatMap { json =>
      IO.fromEither(
        decode[BatchJobDef](json)
          .left.map(e => RuntimeException(s"BatchJobDef decode failed: ${e.getMessage}"))
      )
    }

  def loadBatchJobFromFile(path: java.nio.file.Path): IO[BatchJobDef] =
    IO.blocking(java.nio.file.Files.readString(path)).flatMap(loadBatchJob)