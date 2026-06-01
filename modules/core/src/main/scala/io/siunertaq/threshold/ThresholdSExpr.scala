package io.siunertaq.threshold

sealed trait ThresholdSExpr

object ThresholdSExpr:
  final case class Atom(value: String)            extends ThresholdSExpr
  final case class SList(items: List[ThresholdSExpr]) extends ThresholdSExpr

  given CanEqual[ThresholdSExpr, ThresholdSExpr] = CanEqual.derived

object ThresholdSExprCodec:

  import ThresholdConstraint.*
  import ThresholdSExpr.*

  def toSExpr(problem: ThresholdProblem): ThresholdSExpr =
    SList(Atom("ThresholdProblem") :: problem.constraints.map(toSExpr).toList)

  def fromSExpr(sexpr: ThresholdSExpr): Either[String, ThresholdProblem] = sexpr match
    case SList(Atom("ThresholdProblem") :: constraints) =>
      sequence(constraints.map(fromConstraint)).map(entries => ThresholdProblem(entries.toVector))
    case other =>
      Left(s"ThresholdProblem: unrecognized form: $other")

  def toSExpr(constraint: ThresholdConstraint): ThresholdSExpr = constraint match
    case NonNegative(vertex) =>
      SList(List(Atom("NonNegative"), Atom(ThresholdNames.vertexAtom(vertex))))
    case EqualsConstant(vertex, value) =>
      SList(List(Atom("EqualsConstant"), Atom(ThresholdNames.vertexAtom(vertex)), Atom(value.toString)))
    case FrobeniusGE(src, tgt) =>
      SList(List(Atom("FrobeniusGE"), Atom(ThresholdNames.vertexAtom(src)), Atom(ThresholdNames.vertexAtom(tgt))))
    case VerschiebungLE(src, tgt) =>
      SList(List(Atom("VerschiebungLE"), Atom(ThresholdNames.vertexAtom(src)), Atom(ThresholdNames.vertexAtom(tgt))))
    case DieudonneEq(selmer, affine, prime, leech) =>
      SList(
        List(
          Atom("DieudonneEq"),
          Atom(ThresholdNames.vertexAtom(selmer)),
          Atom(ThresholdNames.vertexAtom(affine)),
          Atom(prime.toString),
          Atom(ThresholdNames.vertexAtom(leech))
        )
      )

  def prettyPrint(sexpr: ThresholdSExpr): String = sexpr match
    case Atom(value) => value
    case SList(items) => s"(${items.map(prettyPrint).mkString(" ")})"

  private def fromConstraint(sexpr: ThresholdSExpr): Either[String, ThresholdConstraint] = sexpr match
    case SList(Atom("NonNegative") :: Atom(vertex) :: Nil) =>
      ThresholdNames.parseVertex(vertex).map(NonNegative.apply)
    case SList(Atom("EqualsConstant") :: Atom(vertex) :: Atom(value) :: Nil) =>
      for
        parsedVertex <- ThresholdNames.parseVertex(vertex)
        parsedValue  <- value.toIntOption.toRight(s"EqualsConstant: invalid int '$value'")
      yield EqualsConstant(parsedVertex, parsedValue)
    case SList(Atom("FrobeniusGE") :: Atom(src) :: Atom(tgt) :: Nil) =>
      for
        srcVertex <- ThresholdNames.parseVertex(src)
        tgtVertex <- ThresholdNames.parseVertex(tgt)
      yield FrobeniusGE(srcVertex, tgtVertex)
    case SList(Atom("VerschiebungLE") :: Atom(src) :: Atom(tgt) :: Nil) =>
      for
        srcVertex <- ThresholdNames.parseVertex(src)
        tgtVertex <- ThresholdNames.parseVertex(tgt)
      yield VerschiebungLE(srcVertex, tgtVertex)
    case SList(Atom("DieudonneEq") :: Atom(selmer) :: Atom(affine) :: Atom(prime) :: Atom(leech) :: Nil) =>
      for
        selmerVertex <- ThresholdNames.parseVertex(selmer)
        affineVertex <- ThresholdNames.parseVertex(affine)
        primeValue   <- prime.toIntOption.toRight(s"DieudonneEq: invalid prime '$prime'")
        leechVertex  <- ThresholdNames.parseVertex(leech)
      yield DieudonneEq(selmerVertex, affineVertex, primeValue, leechVertex)
    case other =>
      Left(s"ThresholdConstraint: unrecognized form: $other")

  private def sequence[A](values: List[Either[String, A]]): Either[String, List[A]] =
    values.foldRight[Either[String, List[A]]](Right(Nil)) { (entry, acc) =>
      for
        head <- entry
        tail <- acc
      yield head :: tail
    }