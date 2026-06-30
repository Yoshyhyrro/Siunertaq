package io.siunertaq.batch

import io.circe.{Decoder, DecodingFailure, HCursor}
import io.siunertaq.BSDVertex
import io.siunertaq.expr.{Instr, Program}

// ─── CondOp ───────────────────────────────────────────────────────────────
//
// dhall-to-json: Dhall union < LT | ... > maps to {"LT": {}} etc.

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
//
// Compare { threshold=4, op=LT } -> {"Compare": {"threshold":4, "op":{"LT":{}}}}
// Even {}  -> {"Even": {}}
// Only {}  -> {"Only": {}}

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

// ─── BSDVertexTag union -> validated vertex tag string ─────────────────────
//
// Dhall's BSDVertexTag union (e.g. {"AffineDual":{}} -> "AffineDual") is
// validated here against io.siunertaq.BSDVertex.fromTag (core), so a Dhall
// tag that no longer matches the Scala enum fails decoding immediately with
// a clear error, instead of silently propagating an unrecognized String.

private def decodeVertexTag(c: HCursor): Either[DecodingFailure, String] =
  c.keys.flatMap(_.headOption)
   .toRight(DecodingFailure("Expected BSDVertexTag union", c.history))
   .flatMap { tag =>
     BSDVertex.fromTag(tag)
       .map(_ => tag)
       .left.map(msg => DecodingFailure(msg, c.history))
   }

// ─── StepDef ──────────────────────────────────────────────────────────────
//
// Note: Decoder[Instr] and Decoder[Program] are resolved automatically via
// io.siunertaq.expr.Instr / Program's companion objects (core) — no explicit
// import needed, now that StackInstrDecoder has moved to core.

final case class StepDef(
  name:       String,
  effectTag:  String,
  cond:       Option[CondExpr],
  normVertex: String,    // "AffineDual", "Selmer", etc. (validated against BSDVertex)
  inputProg:  Program,   // Vector[Instr] - the stack machine instruction sequence
  priority:   Int
) derives CanEqual

object StepDef:
  given Decoder[StepDef] = Decoder.instance { c =>
    for
      name       <- c.downField("name").as[String]
      effectTag  <- c.downField("effect_tag").as[String]
      cond       <- c.downField("cond").as[Option[CondExpr]]
      normVertex <- c.downField("norm_vertex")
               .as(using Decoder.instance(decodeVertexTag))
      inputProg  <- c.downField("input_prog").as[Program]
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