package io.siunertaq.threshold

import io.siunertaq.{ BSDArrow, BSDVertex, FVRole }

enum ThresholdConstraint derives CanEqual:
  case NonNegative(vertex: BSDVertex)
  case EqualsConstant(vertex: BSDVertex, value: Int)
  case FrobeniusGE(src: BSDVertex, tgt: BSDVertex)
  case VerschiebungLE(src: BSDVertex, tgt: BSDVertex)
  case DieudonneEq(selmer: BSDVertex, affine: BSDVertex, prime: Int, leech: BSDVertex)

final case class ThresholdProblem(
  constraints: Vector[ThresholdConstraint]
) derives CanEqual

object ThresholdProblem:

  def fromArrows(arrows: List[BSDArrow[? <: BSDVertex, ? <: BSDVertex]], prime: Int = 7): ThresholdProblem =
    val nonNegative = BSDVertex.values.toVector.map(ThresholdConstraint.NonNegative.apply)

    // "Pop" the .role field out of each arrow.
    //
    // BSDArrow is a parameterised enum with no unapply, so
    //   case BSDArrow(src, tgt, FVRole.Frobenius, _) =>   ← ILLEGAL
    // does not compile.  Instead we access .role directly; the compiler
    // knows a.src / a.tgt are subtypes of BSDVertex from the bound
    // [Src <: BSDVertex, Tgt <: BSDVertex], so no cast is needed.
    val monotonic = arrows.toVector.map { a =>
      a.role match
        case FVRole.Frobenius    => ThresholdConstraint.FrobeniusGE(a.src, a.tgt)
        case FVRole.Verschiebung => ThresholdConstraint.VerschiebungLE(a.src, a.tgt)
    }

    val dieudonne = Vector(
      ThresholdConstraint.DieudonneEq(
        BSDVertex.Selmer,
        BSDVertex.AffineDual,
        prime,
        BSDVertex.Leech
      )
    )
    ThresholdProblem((nonNegative ++ monotonic ++ dieudonne).distinct)

object ThresholdNames:

  def normVar(vertex: BSDVertex): String = vertex.toString.toLowerCase

  def vertexAtom(vertex: BSDVertex): String = vertex.toString

  def parseVertex(name: String): Either[String, BSDVertex] =
    BSDVertex.values.find(_.toString.equalsIgnoreCase(name)) match
      case Some(vertex) => Right(vertex)
      case None         => Left(s"unknown BSDVertex: $name")