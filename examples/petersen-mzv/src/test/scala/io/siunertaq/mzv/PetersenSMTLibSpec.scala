package io.siunertaq.mzv.smt

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// ─────────────────────────────────────────────────────────────────────────
//  PetersenSMTLibSpec
//
//  Unit tests for the SMT-LIB 2 generator (no solver needed).
//  Integration tests (requiring z3) are guarded by RUN_MZV_SMT_SMOKE=1.
//
//  Parallel to YicesThresholdSolverSpec + YicesThresholdSmokeSpec.
// ─────────────────────────────────────────────────────────────────────────

class PetersenSMTLibSpec extends AnyFunSpec with Matchers:

  // ─── Graph sanity ────────────────────────────────────────────────────────

  describe("PetersenSMTLib graph data") {
    it("has exactly 15 undirected edges (3-regular, |V|=10)") {
      PetersenSMTLib.undirectedEdges.size shouldBe 15
    }
    it("every edge has both endpoints in 0..9") {
      PetersenSMTLib.undirectedEdges.foreach { case (a, b) =>
        a should (be >= 0 and be < 10)
        b should (be >= 0 and be < 10)
      }
    }
    it("spoke edges connect Outer(i)=i to Inner(i)=i+5") {
      // all 5 spokes must be present
      (0 until 5).foreach { i =>
        PetersenSMTLib.undirectedEdges should contain ((i, i + 5))
      }
    }
    it("path 0->5->8 is a valid 2-hop route (Outer(0)->Inner(0)->Inner(3))") {
      PetersenSMTLib.undirectedEdges should contain ((0, 5))
      PetersenSMTLib.undirectedEdges should contain ((5, 8))
    }
  }

  // ─── SMT-LIB 2 generator (no solver, just string checks) ─────────────────

  describe("PetersenSMTLib.renderSuite") {
    lazy val suite = PetersenSMTLib.renderSuite()

    it("contains set-logic QF_LIA") {
      suite should include ("QF_LIA")
    }
    it("defines all five shared functions") {
      suite should include ("isValid")
      suite should include ("isOuter")
      suite should include ("isInner")
      suite should include ("adjacent")
      suite should include ("pentaDelta")
      suite should include ("pentaS2")
      suite should include ("pentaS3")
    }
    it("encodes pentaDelta with ite + xor") {
      suite should include ("ite")
      suite should include ("xor")
    }
    it("contains all five property echo labels") {
      suite should include ("P1:")
      suite should include ("P2:")
      suite should include ("P4:")
      suite should include ("P5:")
      suite should include ("P6:")
    }
    it("contains (exit) at the end") {
      suite.trim should endWith ("exit)")
    }
  }

  describe("PetersenSMTLib.renderProperty") {
    it("wraps single property commands with preamble and exit") {
      val smt = PetersenSMTLib.renderProperty(PetersenSMTLib.propDiameter)
      smt should include ("QF_LIA")
      smt should include ("P1:")
      smt should include ("exit)")
    }
  }

  // ─── Smoke test (requires z3 in PATH or Z3_PATH env) ─────────────────────

  private val SmokeEnv = "RUN_MZV_SMT_SMOKE"

  private def smokeEnabled: Boolean =
    sys.env.get(SmokeEnv).exists(v => v == "1" || v.equalsIgnoreCase("true"))

  private def ensureSmoke(): Unit =
    if !smokeEnabled then
      cancel(s"Set $SmokeEnv=1 to run z3 integration tests")
    if !PetersenSmtSolver.isAvailable then
      fail("z3 not found; set Z3_PATH or install via `apt install z3`")

  describe("PetersenSmtSolver.verify (smoke, requires z3)") {
    it("all UNSAT properties return Unsat") {
      ensureSmoke()
      val results = PetersenSmtSolver.verify(PetersenSMTLib.renderSuite())
      results.isRight shouldBe true
      val unsat = results.toOption.get.filter(_.label.contains("expect unsat"))
      unsat.size should be > 0
      unsat.foreach(r => r.result shouldBe MZVVerifyResult.Unsat)
    }
    it("fixed-point property returns Sat with correct model") {
      ensureSmoke()
      val smt = PetersenSMTLib.renderProperty(PetersenSMTLib.propFixedPoint)
      val results = PetersenSmtSolver.verify(smt)
      results.isRight shouldBe true
      val sat = results.toOption.get.find(_.label.contains("P4"))
      sat.isDefined shouldBe true
      sat.get.result match
        case MZVVerifyResult.Sat(model) =>
          model should include ("3")   // mid_s2 = 3
          model should include ("0")   // mid_s3 = 0
        case other => fail(s"Expected Sat, got $other")
    }
  }
