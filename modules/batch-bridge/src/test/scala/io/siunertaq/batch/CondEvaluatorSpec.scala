package io.siunertaq.batch

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers


class CondEvaluatorSpec extends AnyFunSpec with Matchers:

  // ─── JCL COND semantics that is implemented in CondEvaluator.shouldSkip ───────────────────────────────
  //   COND=(threshold, op) :
  //      threshold op maxPrevRC is true → skip
  //
  //   Example: COND=(4,LT) → "4 < maxPrevRC" is true (maxPrevRC > 4) → skip
  //            COND=(0,NE) → "0 ≠ maxPrevRC" is true (maxPrevRC ≠ 0) → skip
  //
  //   EVEN: Always execute even if ABEND occurs (shouldSkip is always false)
  //   ONLY: Execute only if ABEND occurs (no ABEND → skip, ABEND → execute (shouldSkip is true if abended=false, false if abended=true))

  describe("CondEvaluator.shouldSkip") {

    // ─── None ──────────────────────────────────────────────────────────────
    describe("cond = None (COND not specified)") {
      it("RC or ABEND should not affect execution") {
        CondEvaluator.shouldSkip(None, maxRC = 0,  abended = false) shouldBe false
        CondEvaluator.shouldSkip(None, maxRC = 16, abended = false) shouldBe false
        CondEvaluator.shouldSkip(None, maxRC = 0,  abended = true)  shouldBe false
        CondEvaluator.shouldSkip(None, maxRC = 16, abended = true)  shouldBe false
      }
    }

    // ─── COND=EVEN ─────────────────────────────────────────────────────────
    describe("cond = EVEN") {
      it("Always false even if no ABEND") {
        CondEvaluator.shouldSkip(Some(CondExpr.Even), maxRC = 0, abended = false)  shouldBe false
      }
      it("Always false even if ABEND occurs") {
        CondEvaluator.shouldSkip(Some(CondExpr.Even), maxRC = 16, abended = true)  shouldBe false
      }
      it("Always false even if high RC") {
        CondEvaluator.shouldSkip(Some(CondExpr.Even), maxRC = 99, abended = false) shouldBe false
      }
    }

    // ─── COND=ONLY ─────────────────────────────────────────────────────────
    describe("cond = ONLY") {
      it("No ABEND → true (skip)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Only), maxRC = 0,  abended = false) shouldBe true
        CondEvaluator.shouldSkip(Some(CondExpr.Only), maxRC = 12, abended = false) shouldBe true
      }
      it("ABEND occurs → false (execute recovery step)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Only), maxRC = 0,  abended = true)  shouldBe false
        CondEvaluator.shouldSkip(Some(CondExpr.Only), maxRC = 16, abended = true)  shouldBe false
      }
    }

    // ─── Compare: LT (threshold < maxRC → skip) ────────────────────────────
    describe("cond = Compare(_, LT)") {
      it("COND=(4,LT): maxRC=5 → skip (4 < 5)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LT)), maxRC = 5, abended = false) shouldBe true
      }
      it("COND=(4,LT): maxRC=4 → execute (4 not< 4 : boundary)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LT)), maxRC = 4, abended = false) shouldBe false
      }
      it("COND=(4,LT): maxRC=3 → execute (4 not< 3)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LT)), maxRC = 3, abended = false) shouldBe false
      }
    }

    // ─── Compare: LE (threshold <= maxRC → skip) ───────────────────────────
    describe("cond = Compare(_, LE)") {
      it("COND=(4,LE): maxRC=5 → skip (4 <= 5)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LE)), maxRC = 5, abended = false) shouldBe true
      }
      it("COND=(4,LE): maxRC=4 → skip (4 <= 4 : boundary)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LE)), maxRC = 4, abended = false) shouldBe true
      }
      it("COND=(4,LE): maxRC=3 → execute (4 not<= 3)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LE)), maxRC = 3, abended = false) shouldBe false
      }
    }

    // ─── Compare: EQ (threshold == maxRC → skip) ───────────────────────────
    describe("cond = Compare(_, EQ)") {
      it("COND=(0,EQ): maxRC=0 → skip (0 == 0)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.EQ)), maxRC = 0, abended = false) shouldBe true
      }
      it("COND=(0,EQ): maxRC=4 → execute (0 != 4)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.EQ)), maxRC = 4, abended = false) shouldBe false
      }
      it("COND=(8,EQ): maxRC=8 → skip (8 == 8)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(8, CondOp.EQ)), maxRC = 8, abended = false) shouldBe true
      }
    }

    // ─── Compare: NE (most common pattern: COND=(0,NE) = skip if previous RC is not 0) ─
    describe("cond = Compare(_, NE)") {
      it("COND=(0,NE): maxRC=0 → execute (0 == 0, condition false)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 0,  abended = false) shouldBe false
      }
      it("COND=(0,NE): maxRC=4 → skip (0 != 4)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 4,  abended = false) shouldBe true
      }
      it("COND=(0,NE): maxRC=12 → skip (0 != 12)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 12, abended = false) shouldBe true
      }
    }

    // ─── Compare: GT (threshold > maxRC → skip) ────────────────────────────
    describe("cond = Compare(_, GT)") {
      it("COND=(4,GT): maxRC=3 → skip (4 > 3)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GT)), maxRC = 3, abended = false) shouldBe true
      }
      it("COND=(4,GT): maxRC=4 → execute (4 not> 4 : boundary)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GT)), maxRC = 4, abended = false) shouldBe false
      }
      it("COND=(4,GT): maxRC=5 → execute (4 not> 5)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GT)), maxRC = 5, abended = false) shouldBe false
      }
    }

    // ─── Compare: GE (threshold >= maxRC → skip) ───────────────────────────
    describe("cond = Compare(_, GE)") {
      it("COND=(4,GE): maxRC=4 → skip (4 >= 4 : boundary)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GE)), maxRC = 4, abended = false) shouldBe true
      }
      it("COND=(4,GE): maxRC=3 → skip (4 >= 3)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GE)), maxRC = 3, abended = false) shouldBe true
      }
      it("COND=(4,GE): maxRC=5 → execute (4 not>= 5)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GE)), maxRC = 5, abended = false) shouldBe false
      }
    }

    // ─── Compare and abended flags are independent ────────────────────────────────
    // Compare only considers RC. ABEND flag is for ONLY / EVEN.
    describe("Compare and abended flags are independent") {
      it("Even with ABEND, if Compare condition is met, skip") {
        CondEvaluator.shouldSkip(
          Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 4, abended = true
        ) shouldBe true
      }
      it("Even with ABEND, if Compare condition is not met, execute") {
        CondEvaluator.shouldSkip(
          Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 0, abended = true
        ) shouldBe false
      }
    }

    // ─── LT / LE boundary value symmetry ────────────────────────────────────────
    describe("LT and LE boundary value results differ") {
      it("when threshold == maxRC, LT=false / LE=true") {
        val rc = 4
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(rc, CondOp.LT)), maxRC = rc, abended = false) shouldBe false
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(rc, CondOp.LE)), maxRC = rc, abended = false) shouldBe true
      }
      it("when threshold == maxRC, GT=false / GE=true") {
        val rc = 4
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(rc, CondOp.GT)), maxRC = rc, abended = false) shouldBe false
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(rc, CondOp.GE)), maxRC = rc, abended = false) shouldBe true
      }
    }
  }

  // ─── exitStatusToRC  ────────────────────────────────────────────────────────
  describe("CondEvaluator.exitStatusToRC") {
    it("COMPLETED → 0 (successful completion)") {
      CondEvaluator.exitStatusToRC("COMPLETED") shouldBe 0
    }
    it("NOOP → 0 (did nothing = successful)") {
      CondEvaluator.exitStatusToRC("NOOP")      shouldBe 0
    }
    it("STOPPED → 8 (manual stop)") {
      CondEvaluator.exitStatusToRC("STOPPED")   shouldBe 8
    }
    it("FAILED → 12 (error = maximum severity)") {
      CondEvaluator.exitStatusToRC("FAILED")    shouldBe 12
    }
    it("UNKNOWN → 16 (unable to determine = maximum severity)") {
      CondEvaluator.exitStatusToRC("UNKNOWN")   shouldBe 16
    }
    it("Unexpected strings → 4 (equivalent to WARNING)") {
      CondEvaluator.exitStatusToRC("EXECUTING") shouldBe 4
      CondEvaluator.exitStatusToRC("ABANDONED") shouldBe 4
      CondEvaluator.exitStatusToRC("")          shouldBe 4
    }
  }