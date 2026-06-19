package io.siunertaq.postgres

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}

// ─── ClassASTBridge — .class バイトコード → JSONB StackInstr ─────────────────
//
//  StackInstr は Dhall / Scala / PostgreSQL で共通の中間表現。
//  このブリッジにより「JVM が実行したもの」と「Forth が記録したもの」が
//  同じ構造で揃う (向きをそろえた状態)。
//
//  対応オペコード (算術のみ; 制御フローはスキップ):
//    BIPUSH/SIPUSH/ICONST_N → PushScalar
//    IADD                   → AddScalar
//    IMUL                   → MulScalar
//
//  使い方:
//    ClassASTBridge
//      .extractFromBytes(classBytes, targetMethod = "execute")
//      .flatMap(instrJson => registrar.registerStep(..., instructions = instrJson))

object ClassASTBridge:

  // ── JVM opcode → StackInstr JSON ─────────────────────────────────────────
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
      case _                => None  // 制御フロー・型変換等はスキップ

  // ── .class バイト列 → StackInstr JSON 配列 ────────────────────────────────
  def extractFromBytes(
    classBytes:   Array[Byte],
    targetMethod: String = "execute"
  ): IO[Json] =
    IO.blocking {
      val instructions = scala.collection.mutable.ArrayBuffer[Json]()

      val reader = ClassReader(classBytes)
      reader.accept(
        new ClassVisitor(Opcodes.ASM9):
          override def visitMethod(
            access: Int, name: String, descriptor: String,
            signature: String, exceptions: Array[String]
          ): MethodVisitor =
            if name == targetMethod then
              new MethodVisitor(Opcodes.ASM9):
                override def visitIntInsn(opcode: Int, operand: Int): Unit =
                  opcodeToInstr(opcode, Some(operand)).foreach(instructions += _)
                override def visitInsn(opcode: Int): Unit =
                  opcodeToInstr(opcode).foreach(instructions += _)
            else null,
        ClassReader.SKIP_FRAMES
      )

      instructions.toList.asJson
    }

  // ── .class ファイルパス → ForthRegistrar.registerStep() ──────────────────
  def registerFromClassFile(
    path:         java.nio.file.Path,
    args:         ForthOp.RegisterStepArgs,
    targetMethod: String = "execute"
  )(using registrar: ForthRegistrar): IO[String] =
    IO.blocking(java.nio.file.Files.readAllBytes(path))
      .flatMap(extractFromBytes(_, targetMethod))
      .flatMap(instrJson => registrar.registerStep(args.copy(instructions = instrJson)))
