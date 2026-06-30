package io.siunertaq

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// --- NamespaceCanonSpec ------------------------------------------------------
//
//  Verifies that fromJvm and fromPerl converge on the same canonical key for
//  corresponding names, and that suggestPerlPackage / suggestPerlSub produce
//  output consistent with that canonical key. This is the cross-language
//  contract that will back the ClickHouse cross_language_equivalences VIEW.

class NamespaceCanonSpec extends AnyFunSpec with Matchers:

  describe("NamespaceCanon.fromJvm / fromPerl convergence") {

    it("Program.toJson (JVM) and Siunertaq::Expr::Program::to_json (Perl) converge") {
      val jvm  = NamespaceCanon.fromJvm("io.siunertaq.expr.Program", "toJson")
      val perl = NamespaceCanon.fromPerl("Siunertaq::Expr::Program", "to_json")
      jvm.shouldBe(perl)
      jvm.shouldBe("expr.program.to_json")
    }

    it("PerlBridge.generatePerl (JVM) and Siunertaq::Batch::PerlBridge::generate_perl (Perl) converge") {
      val jvm  = NamespaceCanon.fromJvm("io.siunertaq.batch.PerlBridge", "generatePerl")
      val perl = NamespaceCanon.fromPerl("Siunertaq::Batch::PerlBridge", "generate_perl")
      jvm.shouldBe(perl)
      jvm.shouldBe("batch.perlbridge.generate_perl")
    }

    it("top-level class (no subpackage): BSDVertex.fromTag converges") {
      val jvm  = NamespaceCanon.fromJvm("io.siunertaq.BSDVertex", "fromTag")
      val perl = NamespaceCanon.fromPerl("Siunertaq::BSDVertex", "from_tag")
      jvm.shouldBe(perl)
      jvm.shouldBe("bsdvertex.from_tag")
    }

    it("a JVM method name already in snake_case-like form is unaffected by case folding") {
      val jvm  = NamespaceCanon.fromJvm("io.siunertaq.postgres.ClassASTBridge", "extractFromBytes")
      val perl = NamespaceCanon.fromPerl("Siunertaq::Postgres::ClassASTBridge", "extract_from_bytes")
      jvm.shouldBe(perl)
    }

    it("companion-object suffix ($) is stripped from the trailing class segment") {
      NamespaceCanon.fromJvm("io.siunertaq.BSDVertex$", "values")
        .shouldBe(NamespaceCanon.fromJvm("io.siunertaq.BSDVertex", "values"))
    }
  }

  describe("NamespaceCanon.suggestPerlPackage") {

    it("io.siunertaq.expr.Program -> Siunertaq::Expr::Program") {
      NamespaceCanon.suggestPerlPackage("io.siunertaq.expr.Program")
        .shouldBe("Siunertaq::Expr::Program")
    }

    it("io.siunertaq.batch.PerlBridge -> Siunertaq::Batch::PerlBridge") {
      NamespaceCanon.suggestPerlPackage("io.siunertaq.batch.PerlBridge")
        .shouldBe("Siunertaq::Batch::PerlBridge")
    }

    it("top-level class: io.siunertaq.BSDVertex -> Siunertaq::BSDVertex") {
      NamespaceCanon.suggestPerlPackage("io.siunertaq.BSDVertex")
        .shouldBe("Siunertaq::BSDVertex")
    }
  }

  describe("NamespaceCanon.suggestPerlSub") {

    it("toJson -> to_json") {
      NamespaceCanon.suggestPerlSub("toJson").shouldBe("to_json")
    }

    it("fromTag -> from_tag") {
      NamespaceCanon.suggestPerlSub("fromTag").shouldBe("from_tag")
    }

    it("generatePerl -> generate_perl") {
      NamespaceCanon.suggestPerlSub("generatePerl").shouldBe("generate_perl")
    }

    it("is idempotent on names already in snake_case (matches StackMachine.pm convention)") {
      val already = "execute_json"
      NamespaceCanon.suggestPerlSub(already).shouldBe(already)
    }

    it("single lowercase word is unchanged") {
      NamespaceCanon.suggestPerlSub("execute").shouldBe("execute")
    }
  }

  describe("suggestPerlPackage / suggestPerlSub consistency with fromJvm / fromPerl") {

    it("fromJvm(class, method) == fromPerl(suggestPerlPackage(class), suggestPerlSub(method)) for several real names") {
      val cases = List(
        ("io.siunertaq.expr.Program",          "toJson"),
        ("io.siunertaq.batch.PerlBridge",      "generatePerl"),
        ("io.siunertaq.BSDVertex",             "fromTag"),
        ("io.siunertaq.postgres.ClassASTBridge", "extractFromBytes")
      )
      for (className, methodName) <- cases do
        val viaJvm = NamespaceCanon.fromJvm(className, methodName)
        val viaPerl = NamespaceCanon.fromPerl(
          NamespaceCanon.suggestPerlPackage(className),
          NamespaceCanon.suggestPerlSub(methodName)
        )
        viaJvm.shouldBe(viaPerl)
    }
  }
