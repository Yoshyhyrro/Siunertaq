package io.siunertaq.expr

import Expr.*

// ExprTyping mirrors Math_Eval.Type_Of in SPARK/Ada
object ExprTyping:

  def typeOf(expr: Expr): Either[String, Ty] = expr match
    case ConstScalar(_)        => Right(Ty.Scalar)
    case ConstVec3(_, _, _)    => Right(Ty.Vec3)
    case Add(l, r) =>
      for
        lt <- typeOf(l)
        rt <- typeOf(r)
        ty <- Either.cond(
          lt == rt && (lt == Ty.Scalar || lt == Ty.Vec3),
          lt,
          s"Add: type mismatch $lt vs $rt"
        )
      yield ty
    case Mul(l, r) =>
      for
        lt <- typeOf(l)
        rt <- typeOf(r)
        ty <- Either.cond(
          lt == Ty.Scalar && rt == Ty.Scalar,
          Ty.Scalar,
          s"Mul: milestone 1 requires Scalar × Scalar, got $lt × $rt"
        )
      yield ty
    case Dot(l, r) =>
      for
        lt <- typeOf(l)
        rt <- typeOf(r)
        ty <- Either.cond(
          lt == Ty.Vec3 && rt == Ty.Vec3,
          Ty.Scalar,
          s"Dot: requires Vec3 × Vec3, got $lt × $rt"
        )
      yield ty

  def isWellTyped(expr: Expr): Boolean = typeOf(expr).isRight

// ExprEval mirrors Math_Eval.Eval_Expr in SPARK/Ada
object ExprEval:

  def eval(expr: Expr): Either[String, Value] = expr match
    case ConstScalar(n)       => Right(ScalarValue(n))
    case ConstVec3(x, y, z)   => Right(Vec3Value(x, y, z))
    case Add(l, r) =>
      for
        lv <- eval(l)
        rv <- eval(r)
        result <- (lv, rv) match
          case (ScalarValue(a), ScalarValue(b)) =>
            Right(ScalarValue(a + b))
          case (Vec3Value(ax, ay, az), Vec3Value(bx, by, bz)) =>
            Right(Vec3Value(ax + bx, ay + by, az + bz))
          case _ => Left(s"Add: type mismatch at runtime")
      yield result
    case Mul(l, r) =>
      for
        lv <- eval(l)
        rv <- eval(r)
        result <- (lv, rv) match
          case (ScalarValue(a), ScalarValue(b)) => Right(ScalarValue(a * b))
          case _ => Left("Mul: milestone 1 requires Scalar × Scalar")
      yield result
    case Dot(l, r) =>
      for
        lv <- eval(l)
        rv <- eval(r)
        result <- (lv, rv) match
          case (Vec3Value(ax, ay, az), Vec3Value(bx, by, bz)) =>
            Right(ScalarValue(ax * bx + ay * by + az * bz))
          case _ => Left("Dot: requires Vec3 × Vec3")
      yield result

// ProgramEval mirrors Math_Program.Exec_Program in SPARK/Ada
object ProgramEval:

  def execOne(instr: Instr, stack: MachineStack): Either[String, MachineStack] =
    instr match
      case Instr.PushScalar(n)     => Right(ScalarValue(n) :: stack)
      case Instr.PushVec3(x, y, z) => Right(Vec3Value(x, y, z) :: stack)
      case Instr.AddScalar =>
        stack match
          case ScalarValue(a) :: ScalarValue(b) :: rest => Right(ScalarValue(a + b) :: rest)
          case _ => Left("AddScalar: stack underflow or type error")
      case Instr.AddVec3 =>
        stack match
          case Vec3Value(ax, ay, az) :: Vec3Value(bx, by, bz) :: rest =>
            Right(Vec3Value(ax + bx, ay + by, az + bz) :: rest)
          case _ => Left("AddVec3: stack underflow or type error")
      case Instr.MulScalar =>
        stack match
          case ScalarValue(a) :: ScalarValue(b) :: rest => Right(ScalarValue(a * b) :: rest)
          case _ => Left("MulScalar: stack underflow or type error")
      case Instr.DotVec3 =>
        stack match
          case Vec3Value(ax, ay, az) :: Vec3Value(bx, by, bz) :: rest =>
            Right(ScalarValue(ax * bx + ay * by + az * bz) :: rest)
          case _ => Left("DotVec3: stack underflow or type error")

  def exec(program: Program, initial: MachineStack = Nil): Either[String, Value] =
    program
      .foldLeft[Either[String, MachineStack]](Right(initial)) { (acc, instr) =>
        acc.flatMap(execOne(instr, _))
      }
      .flatMap {
        case top :: _ => Right(top)
        case Nil      => Left("exec: empty stack after program execution")
      }

// Lowering mirrors Math_Lowering.Lower in SPARK/Ada
// Main theorem (Scala analog): exec(lower(e).toOption.get) == eval(e)
object Lowering:

  def lower(expr: Expr): Either[String, Program] =
    if !ExprTyping.isWellTyped(expr) then Left("lower: ill-typed expression")
    else Right(lowerUnchecked(expr))

  private def lowerUnchecked(expr: Expr): Program = expr match
    case ConstScalar(n)       => Vector(Instr.PushScalar(n))
    case ConstVec3(x, y, z)   => Vector(Instr.PushVec3(x, y, z))
    case Add(l, r) =>
      val addInstr = ExprTyping.typeOf(l).toOption match
        case Some(Ty.Vec3) => Instr.AddVec3
        case _             => Instr.AddScalar
      lowerUnchecked(l) ++ lowerUnchecked(r) :+ addInstr
    case Mul(l, r) => lowerUnchecked(l) ++ lowerUnchecked(r) :+ Instr.MulScalar
    case Dot(l, r) => lowerUnchecked(l) ++ lowerUnchecked(r) :+ Instr.DotVec3
