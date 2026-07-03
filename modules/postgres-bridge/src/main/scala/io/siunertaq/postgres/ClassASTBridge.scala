package io.siunertaq.postgres

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}

import java.security.MessageDigest

// ─── ClassASTBridge — .class bytecode -> JSONB StackInstr ────────────────────
//
//  StackInstr is the shared intermediate representation across Dhall, Scala,
//  PostgreSQL, and Perl. This bridge ensures that "what the JVM executed" and
//  "what Forth recorded" land in the same format.
//
//  Supported opcodes (arithmetic only; control flow is skipped):
//    BIPUSH / SIPUSH / ICONST_N -> PushScalar
//    IADD                       -> AddScalar
//    IMUL                       -> MulScalar
//
//  Fail-fast contract (changed from the original silent-skip version):
//    - An opcode outside the supported set aborts extraction with
//      UnsupportedBytecodeException, instead of being dropped from the
//      output. A StackInstr array returned by this bridge is either a
//      complete, faithful translation of the target method, or an
//      exception — never a silently partial one.
//    - A target method name that resolves to more than one overload aborts
//      with OverloadedMethodException. Pass targetDescriptor explicitly to
//      disambiguate (e.g. "(I)I").
//    - compileClass additionally refuses to report success if
//      MecrispCompiler had to emit a Stub for any opcode (surfaced via
//      MecrispWordDef.hasDeadCode) unless allowStubs = true.
//
//  Usage:
//    ClassASTBridge
//      .extractFromBytes(classBytes, targetMethod = "execute")
//      .flatMap(instrJson => registrar.registerStep(..., instructions = instrJson))
//
//    ClassASTBridge
//      .compileClass(classBytes, targetMethod = "execute")
//      .flatMap(result => clickHouseSync ! PushCompilation(result.words, result.rows, result.fileHash))

object ClassASTBridge:

  // ── Extraction failures ──────────────────────────────────────────────────
  //
  //  Prior behaviour: opcodeToInstr returning None for an unrecognised opcode
  //  was silently discarded by extractFromBytes, and visitMethod matched by
  //  `name` only (ignoring `descriptor`), silently merging bytecode from every
  //  overload of a method into one instruction list. Both are "confidently
  //  wrong" failure modes: the caller gets back a JSON array that *looks*
  //  complete but is a lossy or conflated view of what the JVM actually runs.
  //  Since the point of this bridge is "we verified this by running the real
  //  JVM", an incomplete or conflated extraction that doesn't announce itself
  //  is worse than an outright failure — it's a false claim of verification.

  final case class UnsupportedBytecodeException(
    className: String, methodName: String, methodDescriptor: String, opcode: Int
  ) extends RuntimeException(
    f"$className.$methodName$methodDescriptor: unsupported/unrecognised opcode 0x$opcode%02x " +
    "— extraction aborted rather than silently dropping this instruction"
  )

  final case class OverloadedMethodException(
    className: String, methodName: String, descriptors: List[String]
  ) extends RuntimeException(
    s"$className.$methodName: ${descriptors.size} overloads found (${descriptors.mkString(", ")}) " +
    "— cannot disambiguate by name alone; pass targetDescriptor explicitly"
  )

  final case class MethodNotFoundException(
    className: String, methodName: String, methodDescriptor: Option[String]
  ) extends RuntimeException(
    s"$className: no method named '$methodName'" +
    methodDescriptor.fold("")(d => s" with descriptor $d") + " found"
  )

  final case class IncompleteTranslationException(
    className: String, methodName: String
  ) extends RuntimeException(
    s"$className.$methodName compiled with at least one Stub instruction (unsupported opcode) " +
    "— refusing to report this as a verified translation. Inspect the resulting " +
    "MecrispWordDef for the STUB comment, or pass allowStubs = true to opt in explicitly."
  )

  // ── JVM opcode -> StackInstr JSON ────────────────────────────────────────
  //  Pure translation table. None means "not yet supported" — callers MUST
  //  treat None as a hard failure (see scanMethod/extractFromBytes below),
  //  never as "skip and continue".
  def opcodeToInstr(opcode: Int, operand: Option[Int] = None): Option[Json] =
    opcode match
      case Opcodes.BIPUSH | Opcodes.SIPUSH =>
        operand.map(n => Json.obj("PushScalar" -> Json.obj("n" -> n.asJson)))
      case Opcodes.ICONST_0 => Some(Json.obj("PushScalar" -> Json.obj("n" -> 0.asJson)))
      case Opcodes.ICONST_1 => Some(Json.obj("PushScalar" -> Json.obj("n" -> 1.asJson)))
      case Opcodes.ICONST_2 => Some(Json.obj("PushScalar" -> Json.obj("n" -> 2.asJson)))
      case Opcodes.ICONST_3 => Some(Json.obj("PushScalar" -> Json.obj("n" -> 3.asJson)))
      case Opcodes.ICONST_4 => Some(Json.obj("PushScalar" -> Json.obj("n" -> 4.asJson)))
      case Opcodes.ICONST_5 => Some(Json.obj("PushScalar" -> Json.obj("n" -> 5.asJson)))
      case Opcodes.IADD     => Some(Json.obj("AddScalar"  -> Json.obj()))
      case Opcodes.IMUL     => Some(Json.obj("MulScalar"  -> Json.obj()))
      case _                => None  // NOT "skip" — see scanMethod

  // ── shared low-level scan ─────────────────────────────────────────────────
  //  Single source of truth for "find the unique method matching
  //  (targetMethod, targetDescriptor) and read its raw (opcode, operand)
  //  stream", reused by both extractFromBytes (-> StackInstr JSON) and
  //  compileClass (-> MecrispCompiler.compile). Keeping this in one place
  //  avoids a second, independently-drifting implementation of the same
  //  overload-detection logic.
  private def scanMethod(
    classBytes:       Array[Byte],
    targetMethod:     String,
    targetDescriptor: Option[String]
  ): IO[(String, String, Seq[(Int, Option[Int])])] =  // (className, descriptor, opcodes)
    IO.blocking {
      val opcodes      = scala.collection.mutable.ArrayBuffer[(Int, Option[Int])]()
      val matched      = scala.collection.mutable.ArrayBuffer[String]()
      var scannedClass = "<unknown>"
      var matchedDesc  = ""

      val reader = ClassReader(classBytes)
      reader.accept(
        new ClassVisitor(Opcodes.ASM9):
          override def visit(
            version: Int, access: Int, name: String, signature: String,
            superName: String, interfaces: Array[String]
          ): Unit =
            scannedClass = name  // ASM internal name, e.g. "io/siunertaq/postgres/Foo"

          override def visitMethod(
            access: Int, name: String, descriptor: String,
            signature: String, exceptions: Array[String]
          ): MethodVisitor =
            if name == targetMethod && targetDescriptor.forall(_ == descriptor) then
              matched += descriptor
              if matched.size > 1 then
                throw OverloadedMethodException(scannedClass, targetMethod, matched.toList)
              matchedDesc = descriptor
              new MethodVisitor(Opcodes.ASM9):
                override def visitIntInsn(opcode: Int, operand: Int): Unit =
                  opcodes += (opcode -> Some(operand))
                override def visitInsn(opcode: Int): Unit =
                  opcodes += (opcode -> None)
            else null,
        ClassReader.SKIP_FRAMES
      )

      if matched.isEmpty then
        throw MethodNotFoundException(scannedClass, targetMethod, targetDescriptor)

      (scannedClass, matchedDesc, opcodes.toSeq)
    }

  // ── .class byte array -> StackInstr JSON array (fail-fast) ───────────────
  //
  //  targetDescriptor: if the target method is overloaded, pass its exact
  //  JVM descriptor (e.g. "(I)I") to disambiguate. Left as None, extraction
  //  requires the name to be unique in the class and raises
  //  OverloadedMethodException otherwise.
  def extractFromBytes(
    classBytes:       Array[Byte],
    targetMethod:     String         = "execute",
    targetDescriptor: Option[String] = None
  ): IO[Json] =
    scanMethod(classBytes, targetMethod, targetDescriptor).map { case (className, descriptor, opcodes) =>
      opcodes.map { case (opcode, operand) =>
        opcodeToInstr(opcode, operand).getOrElse(
          throw UnsupportedBytecodeException(className, targetMethod, descriptor, opcode)
        )
      }.asJson
    }

  // ── .class byte array -> full compilation result (bytecode + Forth word) ─
  //
  //  This is the "v2" entry point that ClickHouseSync.scala's architecture
  //  comment and DeadCodeAnalyzer.scala's input comment both already
  //  reference but that did not exist yet. It reuses scanMethod and
  //  MecrispCompiler.compile rather than re-deriving className/methodName/
  //  opcodes independently.
  //
  //  NOTE: MecrispCompiler.compile currently returns only a MecrispWordDef
  //  (one value per *method*), not the per-instruction breakdown that
  //  BytecodeRow needs (one row per *instruction* — see instructionIdx).
  //  That breakdown exists inside compile() today but only as a private
  //  local val. `rows` is left empty here until MecrispCompiler exposes it,
  //  rather than being reconstructed by a second implementation of the same
  //  stack-depth accounting that could silently drift from the original.
  final case class CompilationResult(
    words:    Vector[MecrispWordDef],
    rows:     Vector[MecrispCompiler.BytecodeRow],
    fileHash: String
  )

  def compileClass(
    classBytes:       Array[Byte],
    targetMethod:     String         = "execute",
    targetDescriptor: Option[String] = None,
    allowStubs:       Boolean        = false
  ): IO[CompilationResult] =
    scanMethod(classBytes, targetMethod, targetDescriptor).flatMap { case (className, descriptor, opcodes) =>
      val word = MecrispCompiler.compile(className, targetMethod, descriptor, opcodes)
      if word.hasDeadCode && !allowStubs then
        IO.raiseError(IncompleteTranslationException(className, targetMethod))
      else
        IO.pure {
          val digest = MessageDigest.getInstance("SHA-256").digest(classBytes)
          val hash   = digest.map(b => f"${b & 0xff}%02x").mkString
          CompilationResult(words = Vector(word), rows = Vector.empty, fileHash = hash)
        }
    }

  // ── .class file path -> ForthRegistrar.registerStep() ───────────────────
  def registerFromClassFile(
    path:             java.nio.file.Path,
    args:             ForthOp.RegisterStepArgs,
    targetMethod:     String         = "execute",
    targetDescriptor: Option[String] = None
  )(using registrar: ForthRegistrar): IO[String] =
    IO.blocking(java.nio.file.Files.readAllBytes(path))
      .flatMap(extractFromBytes(_, targetMethod, targetDescriptor))
      .flatMap(instrJson => registrar.registerStep(args.copy(instructions = instrJson)))