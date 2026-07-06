package io.siunertaq.postgres

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.unsafe.implicits.global
import javax.tools.ToolProvider

// --- JavaFixtureCompiler ------------------------------------------------------
//
//  Compiles a tiny Java source in-memory with the JDK's own javac (via
//  ToolProvider.getSystemJavaCompiler) and returns the resulting .class
//  bytes. This exercises ClassASTBridge against bytecode a real compiler
//  produced, not hand-crafted byte arrays that might not match what javac
//  actually emits — and needs no .class fixtures checked into the repo.
//
//  Requires a JDK (not just a JRE) on the test-running JVM, which CI already
//  has (JAVA_HOME points at a Temurin JDK).

object JavaFixtureCompiler:

  /** `source` must define `public class <className> { ... }`. Returns the
   *  bytes of the compiled <className>.class. */
  def compile(className: String, source: String): Array[Byte] =
    val compiler = ToolProvider.getSystemJavaCompiler
    require(compiler != null, "no system Java compiler available — need a JDK, not just a JRE")

    val dir     = java.nio.file.Files.createTempDirectory("classastbridge-fixture")
    val srcFile = dir.resolve(s"$className.java")
    java.nio.file.Files.writeString(srcFile, source)

    // --release 17 pins the emitted class file's bytecode version to Java 17
    // regardless of which JDK is actually running this test (sbt could be
    // launched under any locally-installed JDK). Without this, a JDK newer
    // than what ASM (9.7 here) supports parsing produces a class file
    // ClassReader can't even open — that's what "Unsupported class file
    // major version 68" (Java 24) means. 17 matches the JDK CI actually
    // builds and runs this project under (see yices_test_ci.yml).
    val result = compiler.run(null, null, null, "--release", "17", "-d", dir.toString, srcFile.toString)
    require(result == 0, s"in-memory compilation of $className failed (javac exit $result)")

    java.nio.file.Files.readAllBytes(dir.resolve(s"$className.class"))

// --- ClassASTBridgeSpec -------------------------------------------------------
//
//  Note on scope: opcodeToInstr currently only covers BIPUSH/SIPUSH/ICONST_N/
//  IADD/IMUL — no ILOAD (parameters/locals), no GETSTATIC/GETFIELD, no
//  INVOKE*. That means almost any *interesting* method body is out of scope
//  today and will (correctly, per the new fail-fast contract) raise
//  UnsupportedBytecodeException. These tests exercise the boundary — that a
//  supported-only body succeeds and an unsupported opcode is caught loudly
//  — rather than asserting on javac's constant-folding behaviour, which is
//  an implementation detail we shouldn't depend on in a test.

class ClassASTBridgeSpec extends AnyFunSpec with Matchers:

  describe("ClassASTBridge.extractFromBytes") {

    it("succeeds on a method whose body is entirely supported opcodes") {
      val bytes = JavaFixtureCompiler.compile("ReturnsSeven",
        """public class ReturnsSeven {
          |  public int execute() { return 7; }
          |}""".stripMargin)

      val json = ClassASTBridge.extractFromBytes(bytes, "execute").unsafeRunSync()
      json.asArray.map(_.size) shouldBe Some(1)  // PushScalar(7) — ireturn is filtered, not an instruction
    }

    it("fails with UnsupportedBytecodeException on a method call (INVOKEVIRTUAL)") {
      val bytes = JavaFixtureCompiler.compile("CallsHashCode",
        """public class CallsHashCode {
          |  public int execute() { return this.hashCode(); }
          |}""".stripMargin)

      an [ClassASTBridge.UnsupportedBytecodeException] should be thrownBy
        ClassASTBridge.extractFromBytes(bytes, "execute").unsafeRunSync()
    }

    it("fails with UnsupportedBytecodeException on a parameter load (ILOAD)") {
      // Documents the current scope limit directly: this is about as simple
      // a "real" method as Java allows, and it's still out of range today.
      val bytes = JavaFixtureCompiler.compile("AddsParam",
        """public class AddsParam {
          |  public int execute(int x) { return x + 4; }
          |}""".stripMargin)

      an [ClassASTBridge.UnsupportedBytecodeException] should be thrownBy
        ClassASTBridge.extractFromBytes(bytes, "execute").unsafeRunSync()
    }

    it("fails with OverloadedMethodException when the target name is overloaded") {
      val bytes = JavaFixtureCompiler.compile("OverloadedExecute",
        """public class OverloadedExecute {
          |  public int execute() { return 1; }
          |  public int execute(int x) { return x; }
          |}""".stripMargin)

      an [ClassASTBridge.OverloadedMethodException] should be thrownBy
        ClassASTBridge.extractFromBytes(bytes, "execute").unsafeRunSync()
    }

    it("disambiguates an overload when targetDescriptor is given") {
      val bytes = JavaFixtureCompiler.compile("OverloadedExecute2",
        """public class OverloadedExecute2 {
          |  public int execute() { return 9; }
          |  public int execute(int x) { return x; }
          |}""".stripMargin)

      val json = ClassASTBridge.extractFromBytes(bytes, "execute", Some("()I")).unsafeRunSync()
      json.asArray.map(_.size) shouldBe Some(1)  // PushScalar(9) from the zero-arg overload only
    }

    it("fails with MethodNotFoundException when the target method doesn't exist") {
      val bytes = JavaFixtureCompiler.compile("NoExecuteHere",
        """public class NoExecuteHere {
          |  public int notExecute() { return 1; }
          |}""".stripMargin)

      an [ClassASTBridge.MethodNotFoundException] should be thrownBy
        ClassASTBridge.extractFromBytes(bytes, "execute").unsafeRunSync()
    }
  }