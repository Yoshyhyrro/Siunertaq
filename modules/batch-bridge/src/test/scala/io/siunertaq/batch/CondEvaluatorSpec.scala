package io.siunertaq.batch

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// 配置先: modules/dhall-bridge/src/test/scala/io/siunertaq/batch/CondEvaluatorSpec.scala

class CondEvaluatorSpec extends AnyFunSpec with Matchers:

  // ─── JCL COND意味論リファレンス ──────────────────────────────────────────
  //
  //   COND=(threshold, op) :
  //     「threshold op maxPrevRC」が真 → 当ステップをスキップ
  //
  //   例: COND=(4,LT) → "4 < maxPrevRC" が真 (前RC > 4) ならスキップ
  //       COND=(0,NE) → "0 ≠ maxPrevRC" が真 (前RC ≠ 0) ならスキップ
  //
  //   EVEN: ABENDがあっても必ず実行 (shouldSkip は常に false)
  //   ONLY: ABENDが発生した場合のみ実行 (ABENDなし → スキップ)

  describe("CondEvaluator.shouldSkip") {

    // ─── None ──────────────────────────────────────────────────────────────
    describe("cond = None (CONDなし)") {
      it("RC・ABENDに関わらず常に false (スキップしない)") {
        CondEvaluator.shouldSkip(None, maxRC = 0,  abended = false) shouldBe false
        CondEvaluator.shouldSkip(None, maxRC = 16, abended = false) shouldBe false
        CondEvaluator.shouldSkip(None, maxRC = 0,  abended = true)  shouldBe false
        CondEvaluator.shouldSkip(None, maxRC = 16, abended = true)  shouldBe false
      }
    }

    // ─── COND=EVEN ─────────────────────────────────────────────────────────
    describe("cond = EVEN") {
      it("ABENDがなくても常に false") {
        CondEvaluator.shouldSkip(Some(CondExpr.Even), maxRC = 0, abended = false)  shouldBe false
      }
      it("ABENDがあっても false (= EVEN は絶対にスキップしない)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Even), maxRC = 16, abended = true)  shouldBe false
      }
      it("高RCでも false") {
        CondEvaluator.shouldSkip(Some(CondExpr.Even), maxRC = 99, abended = false) shouldBe false
      }
    }

    // ─── COND=ONLY ─────────────────────────────────────────────────────────
    describe("cond = ONLY") {
      it("ABEND なし → true (スキップ)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Only), maxRC = 0,  abended = false) shouldBe true
        CondEvaluator.shouldSkip(Some(CondExpr.Only), maxRC = 12, abended = false) shouldBe true
      }
      it("ABEND あり → false (= リカバリステップを実行)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Only), maxRC = 0,  abended = true)  shouldBe false
        CondEvaluator.shouldSkip(Some(CondExpr.Only), maxRC = 16, abended = true)  shouldBe false
      }
    }

    // ─── Compare: LT (threshold < maxRC → skip) ────────────────────────────
    describe("cond = Compare(_, LT)") {
      it("COND=(4,LT): maxRC=5 → スキップ (4 < 5)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LT)), maxRC = 5, abended = false) shouldBe true
      }
      it("COND=(4,LT): maxRC=4 → 実行 (4 not< 4 : 境界値)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LT)), maxRC = 4, abended = false) shouldBe false
      }
      it("COND=(4,LT): maxRC=3 → 実行 (4 not< 3)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LT)), maxRC = 3, abended = false) shouldBe false
      }
    }

    // ─── Compare: LE (threshold <= maxRC → skip) ───────────────────────────
    describe("cond = Compare(_, LE)") {
      it("COND=(4,LE): maxRC=5 → スキップ (4 <= 5)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LE)), maxRC = 5, abended = false) shouldBe true
      }
      it("COND=(4,LE): maxRC=4 → スキップ (4 <= 4 : 境界値で LE は真)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LE)), maxRC = 4, abended = false) shouldBe true
      }
      it("COND=(4,LE): maxRC=3 → 実行 (4 not<= 3)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.LE)), maxRC = 3, abended = false) shouldBe false
      }
    }

    // ─── Compare: EQ (threshold == maxRC → skip) ───────────────────────────
    describe("cond = Compare(_, EQ)") {
      it("COND=(0,EQ): maxRC=0 → スキップ (0 == 0)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.EQ)), maxRC = 0, abended = false) shouldBe true
      }
      it("COND=(0,EQ): maxRC=4 → 実行 (0 != 4)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.EQ)), maxRC = 4, abended = false) shouldBe false
      }
      it("COND=(8,EQ): maxRC=8 → スキップ (8 == 8)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(8, CondOp.EQ)), maxRC = 8, abended = false) shouldBe true
      }
    }

    // ─── Compare: NE (最頻出パターン: COND=(0,NE) = 前RCが0以外ならスキップ) ─
    describe("cond = Compare(_, NE)") {
      it("COND=(0,NE): maxRC=0 → 実行 (0 == 0 なので条件false)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 0,  abended = false) shouldBe false
      }
      it("COND=(0,NE): maxRC=4 → スキップ (0 != 4)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 4,  abended = false) shouldBe true
      }
      it("COND=(0,NE): maxRC=12 → スキップ (0 != 12)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 12, abended = false) shouldBe true
      }
    }

    // ─── Compare: GT (threshold > maxRC → skip) ────────────────────────────
    describe("cond = Compare(_, GT)") {
      it("COND=(4,GT): maxRC=3 → スキップ (4 > 3)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GT)), maxRC = 3, abended = false) shouldBe true
      }
      it("COND=(4,GT): maxRC=4 → 実行 (4 not> 4 : 境界値)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GT)), maxRC = 4, abended = false) shouldBe false
      }
      it("COND=(4,GT): maxRC=5 → 実行 (4 not> 5)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GT)), maxRC = 5, abended = false) shouldBe false
      }
    }

    // ─── Compare: GE (threshold >= maxRC → skip) ───────────────────────────
    describe("cond = Compare(_, GE)") {
      it("COND=(4,GE): maxRC=4 → スキップ (4 >= 4 : 境界値で GE は真)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GE)), maxRC = 4, abended = false) shouldBe true
      }
      it("COND=(4,GE): maxRC=3 → スキップ (4 >= 3)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GE)), maxRC = 3, abended = false) shouldBe true
      }
      it("COND=(4,GE): maxRC=5 → 実行 (4 not>= 5)") {
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(4, CondOp.GE)), maxRC = 5, abended = false) shouldBe false
      }
    }

    // ─── Compare と abended フラグの独立性 ────────────────────────────────
    // Compare はRCのみを見る。ABENDフラグは ONLY / EVEN 専用。
    describe("Compare と abended フラグは独立") {
      it("ABENDありでも Compare 条件が成立すればスキップ") {
        CondEvaluator.shouldSkip(
          Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 4, abended = true
        ) shouldBe true
      }
      it("ABENDありでも Compare 条件が不成立なら実行") {
        CondEvaluator.shouldSkip(
          Some(CondExpr.Compare(0, CondOp.NE)), maxRC = 0, abended = true
        ) shouldBe false
      }
    }

    // ─── LT / LE 境界値の対称性確認 ────────────────────────────────────────
    describe("LT と LE の境界値で結果が異なることを確認") {
      it("threshold == maxRC のとき LT=false / LE=true") {
        val rc = 4
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(rc, CondOp.LT)), maxRC = rc, abended = false) shouldBe false
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(rc, CondOp.LE)), maxRC = rc, abended = false) shouldBe true
      }
      it("threshold == maxRC のとき GT=false / GE=true") {
        val rc = 4
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(rc, CondOp.GT)), maxRC = rc, abended = false) shouldBe false
        CondEvaluator.shouldSkip(Some(CondExpr.Compare(rc, CondOp.GE)), maxRC = rc, abended = false) shouldBe true
      }
    }
  }

  // ─── exitStatusToRC ────────────────────────────────────────────────────────
  describe("CondEvaluator.exitStatusToRC") {
    it("COMPLETED → 0 (正常完了)") {
      CondEvaluator.exitStatusToRC("COMPLETED") shouldBe 0
    }
    it("NOOP → 0 (何もしなかった = 正常扱い)") {
      CondEvaluator.exitStatusToRC("NOOP")      shouldBe 0
    }
    it("STOPPED → 8 (手動停止)") {
      CondEvaluator.exitStatusToRC("STOPPED")   shouldBe 8
    }
    it("FAILED → 12 (エラー)") {
      CondEvaluator.exitStatusToRC("FAILED")    shouldBe 12
    }
    it("UNKNOWN → 16 (判定不能 = 最大重大度)") {
      CondEvaluator.exitStatusToRC("UNKNOWN")   shouldBe 16
    }
    it("既定外の文字列 → 4 (WARNING相当)") {
      CondEvaluator.exitStatusToRC("EXECUTING") shouldBe 4
      CondEvaluator.exitStatusToRC("ABANDONED") shouldBe 4
      CondEvaluator.exitStatusToRC("")          shouldBe 4
    }
  }