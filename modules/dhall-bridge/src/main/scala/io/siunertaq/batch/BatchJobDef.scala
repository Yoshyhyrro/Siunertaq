package io.siunertaq.batch

import io.circe.{Decoder, DecodingFailure, HCursor}
import io.siunertaq.expr.{Instr, Program}

// ─── CondOp ───────────────────────────────────────────────────────────────
// dhall-to-json: CondOp is an all-bare-label Dhall union (an "enum"), which
// dhall-to-json renders as a plain JSON string - "LT", not {"LT":{}}.
// (Confirmed against dhall-json 1.7.12: c.keys on a JSON string returns
// None, so the previous key-based decoder never matched anything.)

enum CondOp derives CanEqual:
  case LT, LE, EQ, NE, GT, GE

object CondOp:
  given Decoder[CondOp] = Decoder.instance { c =>
    c.as[String].flatMap {
      case "LT" => Right(LT);  case "LE" => Right(LE)
      case "EQ" => Right(EQ);  case "NE" => Right(NE)
      case "GT" => Right(GT);  case "GE" => Right(GE)
      case other => Left(DecodingFailure(s"Unknown CondOp: $other", c.history))
    }
  }

// ─── CondExpr ─────────────────────────────────────────────────────────────
// 2026-07: BatchJob.dhall's CondExpr alternatives now carry an explicit
// `tag` field (a singleton-enum discriminator - see BatchJob.dhall's header
// comment) because dhall-to-json strips union constructor names by design,
// and Even/Only both used to collapse to indistinguishable bare {}.
// CondExpr/CondOp are Dhall/batch-specific with no pre-existing JSON
// convention elsewhere, so a discriminator field is the simplest fix here -
// contrast with StackInstr below, which DOES have a pre-existing convention
// to match (Program.toJson).
// dhall-to-json JSON shape now:
//   Compare { threshold=4, op=LT } → {"tag":"Compare","threshold":4,"op":"LT"}
//   Even  → {"tag":"Even"}
//   Only  → {"tag":"Only"}

sealed trait CondExpr derives CanEqual

object CondExpr:
  final case class Compare(threshold: Int, op: CondOp) extends CondExpr
  case object Even extends CondExpr
  case object Only extends CondExpr

  given Decoder[CondExpr] = Decoder.instance { c =>
    c.downField("tag").as[String].flatMap {
      case "Compare" =>
        for
          t  <- c.downField("threshold").as[Int]
          op <- c.downField("op").as[CondOp]
        yield Compare(t, op)
      case "Even" => Right(Even)
      case "Only" => Right(Only)
      case other  => Left(DecodingFailure(s"Unknown CondExpr tag: $other", c.history))
    }
  }

// ─── Dhall StackInstr → expr.Instr ────────────────────────────────────────
// 2026-07: unlike CondExpr/CondOp, StackInstr/input_prog has a PRE-EXISTING
// canonical JSON convention already shared by three other systems - see
// core's Program.scala (Program.toJson), used by ClassASTBridge,
// ForthRegistrar/Postgres JSONB, and Perl's StackMachine.pm. Rather than add
// a fourth, incompatible convention here, BatchJob.dhall now builds this
// exact shape by hand (via Prelude.JSON, bypassing dhall-to-json's automatic
// - and lossy, for this schema - union conversion), so this decoder is
// UNCHANGED from the original: it already expected exactly this shape.
// PushScalar { n=12 } → {"PushScalar": {"n": 12}}
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

// BSDVertexTag union → plain string (it was already an all-bare-label enum,
// e.g. "AffineDual" - only the decoder needed fixing, same root cause as
// CondOp above; no BatchJob.dhall schema change was needed here).
private def decodeVertexTag(c: HCursor): Either[DecodingFailure, String] =
  c.as[String]

// ─── StepDef ──────────────────────────────────────────────────────────────

final case class StepDef(
  name:       String,
  effectTag:  String,
  cond:       Option[CondExpr],
  normVertex: String,    // "AffineDual", "Selmer" etc. - see BatchJob.dhall's BSDVertexTag union
  inputProg:  Program,   // Vector[Instr] — input that will be executed on the stack machine
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