package io.siunertaq.expr

// SExpr mirrors Math_SExpr.SExpr in SPARK/Ada
sealed trait SExpr

object SExpr:
  final case class Atom(value: String)       extends SExpr
  final case class SList(items: List[SExpr]) extends SExpr

  given CanEqual[SExpr, SExpr] = CanEqual.derived

  // toSExpr mirrors Math_SExpr.To_SExpr
  // Round-trip property: fromSExpr(toSExpr(e)) == Right(e) for well-typed e
  def toSExpr(expr: Expr): SExpr = expr match
    case Expr.ConstScalar(n) =>
      SList(List(Atom("ConstScalar"), Atom(n.toString)))
    case Expr.ConstVec3(x, y, z) =>
      SList(List(Atom("ConstVec3"), Atom(x.toString), Atom(y.toString), Atom(z.toString)))
    case Expr.Add(l, r) => SList(List(Atom("Add"), toSExpr(l), toSExpr(r)))
    case Expr.Mul(l, r) => SList(List(Atom("Mul"), toSExpr(l), toSExpr(r)))
    case Expr.Dot(l, r) => SList(List(Atom("Dot"), toSExpr(l), toSExpr(r)))

  // fromSExpr mirrors Math_SExpr.From_SExpr
  def fromSExpr(sexpr: SExpr): Either[String, Expr] = sexpr match
    case SList(Atom("ConstScalar") :: Atom(n) :: Nil) =>
      n.toIntOption.map(Expr.ConstScalar.apply).toRight(s"ConstScalar: invalid int '$n'")
    case SList(Atom("ConstVec3") :: Atom(x) :: Atom(y) :: Atom(z) :: Nil) =>
      for
        xi <- x.toIntOption.toRight(s"ConstVec3: invalid int '$x'")
        yi <- y.toIntOption.toRight(s"ConstVec3: invalid int '$y'")
        zi <- z.toIntOption.toRight(s"ConstVec3: invalid int '$z'")
      yield Expr.ConstVec3(xi, yi, zi)
    case SList(Atom("Add") :: l :: r :: Nil) =>
      for lx <- fromSExpr(l); rx <- fromSExpr(r) yield Expr.Add(lx, rx)
    case SList(Atom("Mul") :: l :: r :: Nil) =>
      for lx <- fromSExpr(l); rx <- fromSExpr(r) yield Expr.Mul(lx, rx)
    case SList(Atom("Dot") :: l :: r :: Nil) =>
      for lx <- fromSExpr(l); rx <- fromSExpr(r) yield Expr.Dot(lx, rx)
    case other =>
      Left(s"fromSExpr: unrecognized form: $other")

  def prettyPrint(sexpr: SExpr): String = sexpr match
    case Atom(v)      => v
    case SList(items) => s"(${items.map(prettyPrint).mkString(" ")})"
