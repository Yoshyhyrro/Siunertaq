package io.siunertaq.expr

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

// Expr mirrors Math_Expr.Expr
// Types are NOT embedded; use ExprTyping.typeOf.
// First milestone: ConstScalar, ConstVec3, Add, Mul (Scalar×Scalar only), Dot (Vec3×Vec3→Scalar).
sealed trait Expr

object Expr:
  final case class ConstScalar(n: Int)                extends Expr
  final case class ConstVec3(x: Int, y: Int, z: Int) extends Expr
  final case class Add(l: Expr, r: Expr)              extends Expr
  final case class Mul(l: Expr, r: Expr)              extends Expr
  final case class Dot(l: Expr, r: Expr)              extends Expr

  given CanEqual[Expr, Expr] = CanEqual.derived
