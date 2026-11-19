package io.siunertaq

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// ─── BSDVertexSpec ────────────────────────────────────────────────────────────
//
//  Verifies that BSDVertex.fromTag / toTag agree with Dhall's BSDVertexTag
//  union. Until now this correspondence was only documented in a comment
//  (dhall-bridge/.../BatchJobDef.scala:67,78).
//
//  Dhall definition (BatchJob.dhall / PetersenMZV.dhall, duplicated in both):
//    let BSDVertexTag = < Leech | AffineDual | Padic | Selmer >
//
//  dhall-to-json renders the tag directly as a JSON key
//  (BSDVertexTag.AffineDual -> {"AffineDual":{}}).

class BSDVertexSpec extends AnyFunSpec with Matchers:

  // All tags in Dhall's BSDVertexTag union.
  // If either BatchJob.dhall or PetersenMZV.dhall changes, update this list too
  // — it pins the Dhall-side canonical form on the Scala side.
  private val dhallTags = List("Leech", "AffineDual", "Padic", "Selmer")

  describe("BSDVertex.fromTag") {

    it("resolves every tag in Dhall's BSDVertexTag union") {
      for tag <- dhallTags do
        BSDVertex.fromTag(tag).isRight.shouldBe(true)
    }

    it("Leech -> BSDVertex.Leech") {
      BSDVertex.fromTag("Leech").shouldBe(Right(BSDVertex.Leech))
    }

    it("AffineDual -> BSDVertex.AffineDual") {
      BSDVertex.fromTag("AffineDual").shouldBe(Right(BSDVertex.AffineDual))
    }

    it("Padic -> BSDVertex.Padic") {
      BSDVertex.fromTag("Padic").shouldBe(Right(BSDVertex.Padic))
    }

    it("Selmer -> BSDVertex.Selmer") {
      BSDVertex.fromTag("Selmer").shouldBe(Right(BSDVertex.Selmer))
    }

    it("unknown tag -> Left") {
      BSDVertex.fromTag("Unknown").isLeft.shouldBe(true)
    }

    it("is case-sensitive, matching Dhall's exact-match union semantics") {
      BSDVertex.fromTag("leech").isLeft.shouldBe(true)
    }
  }

  describe("BSDVertex.toTag") {

    it("maps every BSDVertex value into the Dhall tag set") {
      for v <- BSDVertex.values do
        dhallTags.should(contain(BSDVertex.toTag(v)))
    }
  }

  describe("fromTag / toTag round-trip") {

    it("fromTag(toTag(v)) == v for every BSDVertex value") {
      for v <- BSDVertex.values do
        BSDVertex.fromTag(BSDVertex.toTag(v)).shouldBe(Right(v))
    }

    it("toTag(fromTag(tag)) == tag for every Dhall tag") {
      for tag <- dhallTags do
        BSDVertex.fromTag(tag).map(BSDVertex.toTag).shouldBe(Right(tag))
    }
  }