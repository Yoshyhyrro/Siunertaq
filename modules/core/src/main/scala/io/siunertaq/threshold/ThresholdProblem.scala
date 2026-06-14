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

  def fromArrows(arrows: List[BSDArrow[?, ?]], prime: Int = 7): ThresholdProblem =
    val nonNegative = BSDVertex.values.toVector.map(ThresholdConstraint.NonNegative.apply)
    val monotonic = arrows.toVector.map {
      case BSDArrow(src, tgt, FVRole.Frobenius, _) =>
        ThresholdConstraint.FrobeniusGE(src, tgt)
      case BSDArrow(src, tgt, FVRole.Verschiebung, _) =>
        ThresholdConstraint.VerschiebungLE(src, tgt)
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