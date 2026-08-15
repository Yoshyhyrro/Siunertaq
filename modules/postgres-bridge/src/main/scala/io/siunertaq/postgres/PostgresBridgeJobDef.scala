package io.siunertaq.postgres

import io.circe.Decoder

// ─── PostgresBridgeJobDef ───────────────────────────────────────────────────
//
//  Scala counterpart of PostgresBridgeJob.dhall (this module's
//  src/main/resources), loaded the same way BatchJobDef is loaded from
//  BatchJob.dhall: DhallBatchRegistry.evalDhall (modules/dhall-bridge)
//  shells out to dhall-to-json, and the circe Decoder below does the rest.
//
//  Deliberately NOT StepDef/BatchJobDef (modules/dhall-bridge): those are
//  typed to the BSD Quiver stack machine's Program (StepDef.inputProg), not
//  to compiling a .class file, and StepExecutorActor.runSpringBatchStep
//  hard-codes StackMachineTasklet for every step regardless of type -- see
//  PostgresBridgeApp.scala's header comment for the full reasoning on why
//  this stays a parallel, independently-typed path rather than widening
//  StepDef with a discriminator.

final case class ClassCompilationTarget(
  classFilePath:    String,
  targetMethod:     String,
  targetDescriptor: Option[String],
  allowStubs:       Boolean
) derives CanEqual

object ClassCompilationTarget:
  given Decoder[ClassCompilationTarget] = Decoder.instance { c =>
    for
      path       <- c.downField("class_file_path").as[String]
      method     <- c.downField("target_method").as[String]
      descriptor <- c.downField("target_descriptor").as[Option[String]]
      allowStubs <- c.downField("allow_stubs").as[Boolean]
    yield ClassCompilationTarget(path, method, descriptor, allowStubs)
  }

final case class PostgresBridgeJobDef(
  jobName: String,
  targets: List[ClassCompilationTarget]
) derives CanEqual

object PostgresBridgeJobDef:
  given Decoder[PostgresBridgeJobDef] = Decoder.instance { c =>
    for
      jobName <- c.downField("job_name").as[String]
      targets <- c.downField("targets").as[List[ClassCompilationTarget]]
    yield PostgresBridgeJobDef(jobName, targets)
  }

  // Plain, ordinary-named wrapper around the circe Decoder above, throwing
  // on failure. Exists because external JVM callers (postgres_bridge_
  // job_test.clj, PostgresBridgeApp.scala) would otherwise need to know
  // Scala 3's compiler-generated name for the anonymous `given` instance
  // to invoke it directly -- a named `def` is a stable, ordinary JVM
  // method regardless of how givens happen to compile internally, and
  // gives both callers one shared decode-and-report-errors path instead
  // of two copies of the same match/throw.
  def decodeJson(json: String): PostgresBridgeJobDef =
    io.circe.parser.decode[PostgresBridgeJobDef](json) match
      case Right(v) => v
      case Left(e)  => throw RuntimeException(s"PostgresBridgeJobDef decode failed: ${e.getMessage}")