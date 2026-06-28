package io.siunertaq.expr

import io.circe.Json
import io.circe.syntax.*

// Instr mirrors Math_Program.Instr_Kind in SPARK/Ada
enum Instr derives CanEqual:
  case PushScalar(n: Int)
  case PushVec3(x: Int, y: Int, z: Int)
  case AddScalar
  case AddVec3
  case MulScalar
  case DotVec3

type Program      = Vector[Instr]
type MachineStack = List[Value]

object Program:
  val empty: Program = Vector.empty

  /** Program → 正規 StackInstr JSON 配列。
   *
   *  この形式は3箇所で共有される中間表現:
   *    - ClassASTBridge.opcodeToInstr   (.class → JSON)
   *    - ForthRegistrar.registerStep    (PostgreSQL JSONB instructions 列)
   *    - Siunertaq::StackMachine->execute_json  (Perl 側 JSON::PP)
   *
   *  キー名は ClassASTBridge と完全一致させること。
   *  新しい Instr を追加した場合は StackMachine.pm の execute_json も更新する。
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