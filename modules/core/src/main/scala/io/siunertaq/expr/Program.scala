package io.siunertaq.expr

import io.circe.{Decoder, DecodingFailure, Json}
import io.circe.syntax.*

// Instr mirrors Math_Program.Instr_Kind in SPARK/Ada
enum Instr derives CanEqual:
  case PushScalar(n: Int)
  case PushVec3(x: Int, y: Int, z: Int)
  case AddScalar
  case AddVec3
  case MulScalar
  case DotVec3

object Instr:
  // ─── Canonical StackInstr JSON decoder ─────────────────────────────────────
  //
  //  Paired with Program.toJson (the encode direction). This is the same JSON
  //  shape produced by:
  //    - dhall-to-json, decoding Dhall's `StackInstr` union
  //      (e.g. PushScalar { n = 12 } -> {"PushScalar":{"n":12}})
  //    - ClassASTBridge.opcodeToInstr (.class bytecode -> JSON)
  //    - Siunertaq::StackMachine->execute_json (Perl, consuming the same JSON)
  //
  //  Moved here from dhall-bridge's StackInstrDecoder so that `core` owns both
  //  directions of the Instr <-> JSON correspondence. Any module that already
  //  depends on `core` (batch-bridge, postgres-bridge, dhall-bridge) gets this
  //  decoder for free via companion-object implicit resolution — no import of
  //  `Instr.given` is needed at call sites.
  given Decoder[Instr] = Decoder.instance { c =>
    c.keys.flatMap(_.headOption) match
      case Some("PushScalar") =>
        c.downField("PushScalar").downField("n").as[Int].map(Instr.PushScalar.apply)
      case Some("PushVec3") =>
        val v = c.downField("PushVec3")
        for
          x <- v.downField("x").as[Int]
          y <- v.downField("y").as[Int]
          z <- v.downField("z").as[Int]
        yield Instr.PushVec3(x, y, z)
      case Some("AddScalar") => Right(Instr.AddScalar)
      case Some("AddVec3")   => Right(Instr.AddVec3)
      case Some("MulScalar") => Right(Instr.MulScalar)
      case Some("DotVec3")   => Right(Instr.DotVec3)
      case other             => Left(DecodingFailure(s"Unknown StackInstr: $other", c.history))
  }

type Program      = Vector[Instr]
type MachineStack = List[Value]

object Program:
  val empty: Program = Vector.empty

  /** Program -> canonical StackInstr JSON array.
   *
   *  This format is shared across three layers of the stack:
   *    - ClassASTBridge.opcodeToInstr   (.class bytecode -> JSON)
   *    - ForthRegistrar.registerStep    (PostgreSQL JSONB `instructions` column)
   *    - Siunertaq::StackMachine->execute_json  (Perl, via JSON::PP)
   *
   *  Key names must stay identical to ClassASTBridge's. If a new Instr variant
   *  is added, update Siunertaq::StackMachine.pm's execute_json accordingly.
   */
  def toJson(program: Program): Json =
    Json.fromValues(program.map {
      case Instr.PushScalar(n)     => Json.obj("PushScalar" -> Json.obj("n" -> n.asJson))
      case Instr.PushVec3(x, y, z) => Json.obj("PushVec3"  -> Json.obj(
                                        "x" -> x.asJson, "y" -> y.asJson, "z" -> z.asJson))
      case Instr.AddScalar         => Json.obj("AddScalar"  -> Json.obj())
      case Instr.AddVec3           => Json.obj("AddVec3"    -> Json.obj())
      case Instr.MulScalar         => Json.obj("MulScalar"  -> Json.obj())
      case Instr.DotVec3           => Json.obj("DotVec3"    -> Json.obj())
    })

  /** JSON array -> Program. Inverse of toJson; built from the companion's
   *  Decoder[Instr], decoding a JSON array and converting to Vector. */
  given Decoder[Program] = summon[Decoder[List[Instr]]].map(_.toVector)

object Stack:
  val empty: MachineStack = Nil

  def requiredDepth(instr: Instr): Int = instr match
    case Instr.PushScalar(_)     => 0
    case Instr.PushVec3(_, _, _) => 0
    case _                       => 2

  def depthDelta(instr: Instr): Int = instr match
    case Instr.PushScalar(_)     => +1
    case Instr.PushVec3(_, _, _) => +1
    case _                       => -1   // pop 2, push 1