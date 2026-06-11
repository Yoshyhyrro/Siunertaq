package io.siunertaq.batch

import io.circe.{Decoder, DecodingFailure, HCursor}
import io.siunertaq.expr.{Instr, Program}

// ─── CondOp ───────────────────────────────────────────────────────────────
// dhall-to-json: Dhall union < LT | ... > → {"LT": {}} 形式のJSON

enum CondOp derives CanEqual:
  case LT, LE, EQ, NE, GT, GE

object CondOp:
  given Decoder[CondOp] = Decoder.instance { c =>
    c.keys.flatMap(_.headOption) match
      case Some("LT") => Right(LT);  case Some("LE") => Right(LE)
      case Some("EQ") => Right(EQ);  case Some("NE") => Right(NE)
      case Some("GT") => Right(GT);  case Some("GE") => Right(GE)
      case other      => Left(DecodingFailure(s"Unknown CondOp: $other", c.history))
  }

// ─── CondExpr ─────────────────────────────────────────────────────────────
// Compare { threshold=4, op=LT } → {"Compare": {"threshold":4, "op":{"LT":{}}}}
// Even {}  → {"Even": {}}
// Only {}  → {"Only": {}}

sealed trait CondExpr derives CanEqual

object CondExpr:
  final case class Compare(threshold: Int, op: CondOp) extends CondExpr
  case object Even extends CondExpr
  case object Only extends CondExpr

  given Decoder[CondExpr] = Decoder.instance { c =>
    c.keys.flatMap(_.headOption) match
      case Some("Compare") =>
        val inner = c.downField("Compare")
        for
          t  <- inner.downField("threshold").as[Int]
          op <- inner.downField("op").as[CondOp]
        yield Compare(t, op)
      case Some("Even") => Right(Even)
      case Some("Only") => Right(Only)
      case other        => Left(DecodingFailure(s"Unknown CondExpr: $other", c.history))
  }

// ─── Dhall StackInstr → expr.Instr ────────────────────────────────────────
// PushScalar { n=12 } → {"PushScalar": {"n":12}}
// AddScalar {}        → {"AddScalar": {}}

object StackInstrDecoder:
  given Decoder[Instr] = Decoder.instance { c =>
    c.keys.flatMap(_.headOption) match
      case Some("PushScalar") =>
        c.downField("PushScalar").downField("n").as[Int].map(Instr.PushScalar.apply)
      case Some("PushVec3") =>
        val v = c.downField("PushVec3")
        for x <- v.downField("x").as[Int]; y <- v.downField("y").as[Int]
            z <- v.downField("z").as[Int]
        yield Instr.PushVec3(x, y, z)
      case Some("AddScalar") => Right(Instr.AddScalar)
      case Some("AddVec3")   => Right(Instr.AddVec3)
      case Some("MulScalar") => Right(Instr.MulScalar)
      case Some("DotVec3")   => Right(Instr.DotVec3)
      case other             => Left(DecodingFailure(s"Unknown StackInstr: $other", c.history))
  }

// BSDVertexTag union → キー文字列 (例: {"AffineDual":{}} → "AffineDual")
private def decodeVertexTag(c: HCursor): Either[DecodingFailure, String] =
  c.keys.flatMap(_.headOption)
   .toRight(DecodingFailure("Expected BSDVertexTag union", c.history))

// ─── StepDef ──────────────────────────────────────────────────────────────

final case class StepDef(
  name:       String,
  effectTag:  String,
  cond:       Option[CondExpr],
  normVertex: String,    // "AffineDual", "Selmer" 等 (BSDVertex.toString と一致)
  inputProg:  Program,   // Vector[Instr] — スタックマシン入力
  priority:   Int
) derives CanEqual

object StepDef:
  import StackInstrDecoder.given
  given Decoder[StepDef] = Decoder.instance { c =>
    for
      name       <- c.downField("name").as[String]
      effectTag  <- c.downField("effect_tag").as[String]
      cond       <- c.downField("cond").as[Option[CondExpr]]
      normVertex <- c.downField("norm_vertex")
               .as(using Decoder.instance(decodeVertexTag))
      inputProg  <- c.downField("input_prog").as[List[Instr]].map(_.toVector)
      priority   <- c.downField("priority").as[Int]
    yield StepDef(name, effectTag, cond, normVertex, inputProg, priority)
  }

// ─── BatchJobDef ──────────────────────────────────────────────────────────

final case class BatchJobDef(
  jobName: String,
  prime:   Int,
  steps:   List[StepDef]
) derives CanEqual

object BatchJobDef:
  given Decoder[BatchJobDef] = Decoder.instance { c =>
    for
      jobName <- c.downField("job_name").as[String]
      prime   <- c.downField("prime").as[Int]
      steps   <- c.downField("steps").as[List[StepDef]]
    yield BatchJobDef(jobName, prime, steps)
  }