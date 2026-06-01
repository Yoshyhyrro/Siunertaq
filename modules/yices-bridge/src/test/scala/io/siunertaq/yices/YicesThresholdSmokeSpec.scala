package io.siunertaq.yices

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import io.siunertaq.BSDArrow
import io.siunertaq.BSDVertex
import io.siunertaq.threshold.ThresholdConstraint
import io.siunertaq.threshold.ThresholdProblem
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class YicesThresholdSmokeSpec extends AnyFunSpec with Matchers:

  private val SmokeEnv       = "RUN_YICES_SMOKE"
  private val ArtifactDirEnv = "YICES_ARTIFACT_DIR"

  private def smokeEnabled: Boolean =
    sys.env.get(SmokeEnv).exists(value => value == "1" || value.equalsIgnoreCase("true"))

  private def artifactDir: Option[Path] =
    sys.env.get(ArtifactDirEnv).map(Paths.get(_))

  private def writeArtifact(fileName: String, content: String): Unit =
    artifactDir.foreach { dir =>
      Files.createDirectories(dir)
      Files.writeString(dir.resolve(fileName), content, StandardCharsets.UTF_8)
    }

  private def ensureSmokeReady(): Unit =
    if !smokeEnabled then
      cancel(s"Set $SmokeEnv=1 to run Yices integration smoke tests")
    if !YicesThresholdSolver.isAvailable then
      fail("Yices smoke tests were enabled but yices-smt2 is not available")

  describe("YicesThresholdSolver.verify") {
    it("proves a satisfiable threshold problem with the real solver") {
      ensureSmokeReady()

      val problem = ThresholdProblem.fromArrows(List(BSDArrow.tensorBang(cats.effect.IO.unit)), prime = 7)
      writeArtifact("sat-problem.smt2", YicesSmtLib.renderCheck(problem))

      val result = YicesThresholdSolver.verify(problem)
      writeArtifact("sat-result.txt", result.fold(identity, identity))

      result.isRight shouldBe true
    }

    it("reports UNSAT for contradictory constant constraints") {
      ensureSmokeReady()

      val problem = ThresholdProblem(
        Vector(
          ThresholdConstraint.EqualsConstant(BSDVertex.Leech, 0),
          ThresholdConstraint.EqualsConstant(BSDVertex.Leech, 1)
        )
      )
      writeArtifact("unsat-problem.smt2", YicesSmtLib.renderCheck(problem))

      val result = YicesThresholdSolver.verify(problem)
      writeArtifact("unsat-result.txt", result.fold(identity, identity))

      result shouldBe Left("UNSAT: Yices 2 determined the norm constraints are unsatisfiable")
    }
  }