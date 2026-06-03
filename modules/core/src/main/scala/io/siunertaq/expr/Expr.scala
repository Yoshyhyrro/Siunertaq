package io.siunertaq.expr


// ── Untyped expression ADT ──
enum Expr:
  case ConstScalar(n: Int)
  case ConstVec3(x: Int, y: Int, z: Int)
  case Add(l: Expr, r: Expr)
  case Mul(l: Expr, r: Expr)
  case Dot(l: Expr, r: Expr)

// ── Stack-machine instruction set ──
enum Instr:
  case PushScalar(n: Int)
  case PushVec3(x: Int, y: Int, z: Int)
  case AddScalar
  case AddVec3
  case MulScalar
  case DotVec3

// ── Type aliases ──
type Program      = Vector[Instr]
type MachineStack = List[Value]

// Ty mirrors Math_Types.Ty in SPARK/Ada
enum Ty derives CanEqual:
  case Scalar
  case Vec3

// Value mirrors Math_Types.Value
sealed trait Value:
  def ty: Ty

final case class ScalarValue(n: Int) extends Value:
  val ty: Ty = Ty.Scalar

final case class Vec3Value(x: Int, y: Int, z: Int) extends Value:
  val ty: Ty = Ty.Vec3

object Value:
  given CanEqual[Value, Value] = CanEqual.derived

// Strongly-typed expressions leveraging Generalized Algebraic Data Types (GADT).
// This ensures that type safety is guaranteed at compile-time,
// eliminating the need for runtime type checking (e.g., ExprTyping.typeOf).
//
// Note: In Scala 3, parameterless enum cases are values, so we use `.type` 
// to refer to their singleton types (e.g., Ty.Scalar.type).
sealed trait TypedExpr[T <: Ty]

object TypedExpr:
  final case class TConstScalar(n: Int) extends TypedExpr[Ty.Scalar.type]
  final case class TConstVec3(x: Int, y: Int, z: Int) extends TypedExpr[Ty.Vec3.type]
  
  // TAdd is generic over T <: Ty, ensuring we only add Scalar to Scalar or Vec3 to Vec3.
  final case class TAdd[T <: Ty](l: TypedExpr[T], r: TypedExpr[T]) extends TypedExpr[T]
  
  // TMul is restricted to Scalar * Scalar -> Scalar.
  final case class TMul(l: TypedExpr[Ty.Scalar.type], r: TypedExpr[Ty.Scalar.type]) extends TypedExpr[Ty.Scalar.type]
  
  // TDot takes two Vec3 and returns a Scalar.
  final case class TDot(l: TypedExpr[Ty.Vec3.type], r: TypedExpr[Ty.Vec3.type]) extends TypedExpr[Ty.Scalar.type]

  // Allow equality checks between typed expressions
  given [T <: Ty]: CanEqual[TypedExpr[T], TypedExpr[T]] = CanEqual.derived