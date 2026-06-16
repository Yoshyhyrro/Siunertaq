package io.siunertaq.yices

import java.nio.charset.StandardCharsets

import io.siunertaq.{ BSDArrow, BSDVertex }
import io.siunertaq.threshold.{ ThresholdConstraint, ThresholdNames, ThresholdProblem }

enum YicesStatus derives CanEqual:
  case Sat
  case Unsat
  case Unknown

object YicesSmtLib:

  def renderCheck(problem: ThresholdProblem): String =
    val declarations = io.siunertaq.BSDVertex.values.toVector.map { vertex =>
      s"(declare-fun ${ThresholdNames.normVar(vertex)} () Real)"
    }
    val assertions = problem.constraints.map(renderConstraint)
    (Vector("(set-logic QF_NRA)") ++ declarations ++ assertions ++ Vector("(check-sat)"))
      .mkString("\n")

  def renderModel(problem: ThresholdProblem): String =
    renderCheck(problem) + "\n(get-model)\n"

  private def renderConstraint(constraint: ThresholdConstraint): String = constraint match
    case ThresholdConstraint.NonNegative(vertex) =>
      s"(assert (>= ${ThresholdNames.normVar(vertex)} 0))"
    case ThresholdConstraint.EqualsConstant(vertex, value) =>
      s"(assert (= ${ThresholdNames.normVar(vertex)} $value))"
    case ThresholdConstraint.FrobeniusGE(src, tgt) =>
      s"(assert (>= ${ThresholdNames.normVar(tgt)} ${ThresholdNames.normVar(src)}))"
    case ThresholdConstraint.VerschiebungLE(src, tgt) =>
      s"(assert (<= ${ThresholdNames.normVar(tgt)} ${ThresholdNames.normVar(src)}))"
    case ThresholdConstraint.DieudonneEq(selmer, affine, prime, leech) =>
      s"(assert (= (* ${ThresholdNames.normVar(selmer)} ${ThresholdNames.normVar(affine)}) (* $prime ${ThresholdNames.normVar(leech)})))"

object YicesThresholdSolver:

  private final case class ProcessResult(exitCode: Int, stdout: String, stderr: String)

  private def yicesBin: String =
    sys.env.getOrElse("YICES_SMT2", "yices-smt2")

  def verify(arrows: List[BSDArrow[? <: BSDVertex, ? <: BSDVertex]], prime: Int = 7): Either[String, String] =
    verify(ThresholdProblem.fromArrows(arrows, prime))

  def verify(problem: ThresholdProblem): Either[String, String] =
    run(YicesSmtLib.renderCheck(problem)).flatMap { checkResult =>
      parseStatus(checkResult.stdout).flatMap {
        case YicesStatus.Sat =>
          run(YicesSmtLib.renderModel(problem)).map { modelResult =>
            extractModel(modelResult.stdout)
          }
        case YicesStatus.Unsat =>
          Left("UNSAT: Yices 2 determined the norm constraints are unsatisfiable")
        case YicesStatus.Unknown =>
          Left(s"UNKNOWN: Yices 2 could not reach a conclusion\n${checkResult.stdout}\n${checkResult.stderr}".trim)
      }
    }

  def parseStatus(output: String): Either[String, YicesStatus] =
    output.linesIterator.map(_.trim).find(_.nonEmpty) match
      case Some("sat")     => Right(YicesStatus.Sat)
      case Some("unsat")   => Right(YicesStatus.Unsat)
      case Some("unknown") => Right(YicesStatus.Unknown)
      case Some(other)      => Left(s"Unable to interpret Yices 2 status: $other")
      case None             => Left("Received empty response from Yices 2")

  def isAvailable: Boolean =
    try
      val process = ProcessBuilder(yicesBin, "--version").start()
      val exitCode = process.waitFor()
      exitCode == 0
    catch
      case _: Throwable => false

  private def extractModel(output: String): String =
    output.linesIterator.dropWhile(_.trim != "sat").drop(1).mkString("\n").trim match
      case "" => "sat"
      case model => model

  private def run(input: String): Either[String, ProcessResult] =
    try
      val process = ProcessBuilder(yicesBin).start()
      val stdin = process.getOutputStream
      stdin.write(input.getBytes(StandardCharsets.UTF_8))
      stdin.close()

      val stdout = scala.io.Source.fromInputStream(process.getInputStream).mkString
      val stderr = scala.io.Source.fromInputStream(process.getErrorStream).mkString
      val exitCode = process.waitFor()

      if exitCode == 0 then
        Right(ProcessResult(exitCode, stdout, stderr))
      else
        Left(s"yices-smt2 failed (exit $exitCode): $stderr".trim)
    catch
      case err: Throwable =>
        Left(s"Failed to start yices-smt2: ${err.getMessage}")