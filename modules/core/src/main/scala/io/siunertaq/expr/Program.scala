package io.siunertaq.expr

// Instr mirrors Math_Program.Instr_Kind in SPARK/Ada
enum Instr derives CanEqual:
  case PushScalar(n: Int)
  case PushVec3(x: Int, y: Int, z: Int)
  case AddScalar
  case AddVec3
  case MulScalar
  case DotVec3

type Program    = Vector[Instr]
type MachineStack = List[Value]

object Program:
  val empty: Program = Vector.empty

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

  def wellStacked(program: Program, initial: MachineStack): Boolean =
    program.foldLeft((true, initial.length)) { case ((ok, depth), instr) =>
      if !ok then (false, depth)
      else if depth < requiredDepth(instr) then (false, depth)
      else (true, depth + depthDelta(instr))
    }._1
