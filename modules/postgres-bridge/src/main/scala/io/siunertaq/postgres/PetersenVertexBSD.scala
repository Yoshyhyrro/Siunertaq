package io.siunertaq.postgres

import io.siunertaq.BSDVertex
import io.siunertaq.batch.{CondExpr, CondOp}
import io.siunertaq.mzv.domain.{Vertex, Outer, Inner}

// ─── PetersenVertexBSD — PetersenMZV.dhall の Scala ミラー ───────────────────
//
//  Dhall: vertexToBSD / vertexToCond
//  Scala: vertexToBSD / vertexToCond
//
//  どちらも同じ写像を表す。dhall-to-json パスと Scala 直接パスで
//  結果が一致することがテストで保証されるべき不変量。

object PetersenVertexBSD:

  /** Petersen 頂点 → BSD quiver 頂点
   *
   *  Outer(0)    → Leech       (norm-base, W0)
   *  Outer(1..2) → AffineDual  (midpoint,  W12)
   *  Outer(3..4) → Padic       (outer rim, W8)
   *  Inner(0)    → Selmer      (divergent pole, W16)
   *  Inner(1..4) → AffineDual  (recovery target)
   */
  def vertexToBSD(v: Vertex): BSDVertex = v match
    case Outer(0)           => BSDVertex.Leech
    case Outer(p) if p < 3  => BSDVertex.AffineDual
    case Outer(_)           => BSDVertex.Padic
    case Inner(0)           => BSDVertex.Selmer
    case Inner(_)           => BSDVertex.AffineDual

  /** COND 式の導出 — MZV 収束性と JCL COND の同型
   *
   *  Outer       → None          topology ??? dead code (P1/UNSAT)
   *  Inner(0)    → COND=ONLY     Selmer; ImaginaryPopper ABEND 時のみ実行
   *  Inner(i≥1)  → COND=(0,NE)  AffineDual recovery; エラー RC 時のみ実行
   */
  def vertexToCond(v: Vertex): Option[CondExpr] = v match
    case Outer(_)  => None
    case Inner(0)  => Some(CondExpr.Only)
    case Inner(_)  => Some(CondExpr.Compare(0, CondOp.NE))

  /** effect_tag の導出 */
  def vertexToEffectTag(v: Vertex): String = v match
    case Outer(p) => s"outer_phase_$p"
    case Inner(p) => s"inner_phase_$p"
