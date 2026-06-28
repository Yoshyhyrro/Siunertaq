
package io.siunertaq.batch

import cats.effect.IO
import io.siunertaq.expr.{Program, ProgramEval, Value}
import io.siunertaq.expr.ProgramLifter

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.util.Comparator

/** Cross-verification of the stack machine using Perl (Strawberry / Ubuntu system perl).
 *
 *  OS-specific binary resolution:
 *    Windows  → C:\Strawberry\perl\bin\perl.exe → perl.exe in PATH
 *    Linux etc. → /usr/bin/perl → /usr/local/bin/perl → perl in PATH
 *
 *  Activation condition: Environment variable RUN_PERL_CROSSCHECK=1 must be set.
 *
 *  Intermediate JSON representation (Program.toJson):
 *    Scala Program → JSON → Siunertaq::StackMachine->execute_json (Perl)
 *    This same JSON is used as a common intermediate representation for 
 *    ClassASTBridge (.class → JSON) and ForthRegistrar.registerStep (PostgreSQL JSONB).
 *
 *  Script layout:
 *    $tmpDir/
 *      $label.pl              ← Thin glue script passing Program.toJson to execute_json
 *      Siunertaq/
 *        StackMachine.pm      ← Extracted from classpath resource (implemented with JSON::PP)
 *
 *  Persistence of generated scripts (for CI artifact collection):
 *    If PERL_SCRIPT_SAVE_DIR is set, .pl and Siunertaq/StackMachine.pm are saved.
 *    Specifying ci-out/perl-scripts/ allows them to be archived as artifacts alongside .class files.
 *
 *  maybeCheckIO return value semantics:
 *    Right(v)                     → Scala and Perl results match; v is the common value
 *    Left("[PerlBridge] SKIP: …") → Cross-check disabled / perl not found / lift failed
 *    Left("MISMATCH: …")          → Values diverge → StepExecutorActor treats as FAILED
 *    Left(other)                  → Perl execution / parse error → treated as WARN
 */
object PerlBridge:

  // ─── OS Detection ──────────────────────────────────────────────────────────
  private val isWindows: Boolean =
    System.getProperty("os.name", "").toLowerCase.contains("win")

  // Search for executable binaries in PATH (excluding empty directories)
  private def findInPath(names: String*): Option[String] =
    val sep  = if isWindows then ";" else ":"
    val dirs = Option(System.getenv("PATH"))
      .getOrElse("")
      .split(sep)
      .filter(_.nonEmpty)
      .toIndexedSeq
    names.view
      .flatMap(n => dirs.map(d => Paths.get(d, n)))
      .find(Files.isExecutable)
      .map(_.toString)

  /** Full path to the perl binary (lazy and thread-safe).
   *
   *  Ubuntu 26 defaults to /usr/bin/perl.
   *  Strawberry Perl standard installation path: C:\Strawberry\perl\bin\perl.exe.
   */
  lazy val perlBinary: Option[String] =
    if isWindows then
      // Prioritize fixed Strawberry Perl paths
      List(
        raw"C:\Strawberry\perl\bin\perl.exe",
        raw"C:\strawberry\perl\bin\perl.exe"
      ).find(p => Files.isExecutable(Paths.get(p)))
        .orElse(findInPath("perl.exe", "perl"))
    else
      // Ubuntu / Debian / macOS: Prioritize system perl
      List("/usr/bin/perl", "/usr/local/bin/perl")
        .find(p => Files.isExecutable(Paths.get(p)))
        .orElse(findInPath("perl"))

  // ─── StackMachine.pm classpath resource ──────────────────────────────────
  //  Loaded from /perl/Siunertaq/StackMachine.pm in the batch-bridge JAR.
  //  Loaded only on first access (lazy + thread-safe).
  private lazy val stackMachinePm: String =
    val path = "/perl/Siunertaq/StackMachine.pm"
    Option(getClass.getResourceAsStream(path))
      .map { stream =>
        try String(stream.readAllBytes(), StandardCharsets.UTF_8)
        finally stream.close()
      }
      .getOrElse(throw new java.io.FileNotFoundException(
        s"Classpath resource not found: $path — " +
        "Please place StackMachine.pm in batch-bridge/src/main/resources/perl/Siunertaq/"
      ))

  // ─── Program → Thin glue .pl ──────────────────────────────────────────────
  //  Execution logic resides in Siunertaq::StackMachine->execute_json (Perl side).
  //  printMethod: "print_scalar" | "print_vec3"
  private def toPerlScript(program: Program, printMethod: String): String =
    val json = Program.toJson(program).noSpaces
    s"""#!/usr/bin/perl
       |use strict;
       |use warnings;
       |use FindBin;
       |use lib "$$FindBin::Bin";
       |use Siunertaq::StackMachine;
       |my $$sm = Siunertaq::StackMachine->new();
       |$$sm->execute_json('${json.replace("'", "\\'")}');
       |$$sm->$printMethod();
       |""".stripMargin

  // ─── Perl subprocess execution ─────────────────────────────────────────────────
  //
  //  Using IO.blocking to avoid blocking Spring Batch management threads.
  //
  //  Execution directory structure:
  //    $tmpDir/
  //      $label.pl              ← .pl script (FindBin::Bin = $tmpDir)
  //      Siunertaq/
  //        StackMachine.pm      ← Extracted from classpath; referenced via 'use lib'
  //
  //  If PERL_SCRIPT_SAVE_DIR is set, a copy is saved using the same structure.
  def executePerl(script: String, perl: String, label: String): IO[Either[String, String]] =
    IO.blocking:
      val tmpDir    = Files.createTempDirectory("siunertaq-perl-")
      val scriptFile = tmpDir.resolve(s"$label.pl")
      val pmDir      = tmpDir.resolve("Siunertaq")
      try
        // Write .pl script
        Files.writeString(scriptFile, script)

        // Extract Siunertaq/StackMachine.pm from classpath
        Files.createDirectories(pmDir)
        Files.writeString(pmDir.resolve("StackMachine.pm"), stackMachinePm)

        // For CI artifact collection: permanently save .pl and .pm to PERL_SCRIPT_SAVE_DIR
        sys.env.get("PERL_SCRIPT_SAVE_DIR").foreach { saveDir =>
          val dest   = Paths.get(saveDir)
          val destPm = dest.resolve("Siunertaq")
          Files.createDirectories(dest)
          Files.createDirectories(destPm)
          Files.copy(scriptFile,
            dest.resolve(s"$label.pl"), StandardCopyOption.REPLACE_EXISTING)
          Files.copy(pmDir.resolve("StackMachine.pm"),
            destPm.resolve("StackMachine.pm"), StandardCopyOption.REPLACE_EXISTING)
        }

        // Invoke perl within tmpDir ($FindBin::Bin = tmpDir)
        val proc = new ProcessBuilder(perl, scriptFile.toString)
          .directory(tmpDir.toFile)
          .redirectErrorStream(true)
          .start()
        val out  = String(proc.getInputStream.readAllBytes()).trim
        val exit = proc.waitFor()
        if exit == 0 then Right(out)
        else Left(s"perl exited $exit: $out")
      finally
        // Recursively delete tmpDir (bottom-up)
        Files.walk(tmpDir)
          .sorted(Comparator.reverseOrder())
          .forEach(p => Files.deleteIfExists(p): Unit)

  // ─── Public API (for tests and external calls) ──────────────────────────────────

  /** Checks if perl binary is available (for testing/guards). */
  def isAvailable: Boolean = perlBinary.isDefined

  /** Binary name for error messages (defaults to "perl" if not found). */
  def perlBin: String = perlBinary.getOrElse("perl")

  /** Public wrapper to verify script generation from outside (PerlBridgeSpec §3).
   *  printMethod: "print_scalar" | "print_vec3"
   */
  def generatePerl(program: Program, printMethod: String): String =
    toPerlScript(program, printMethod)

  /** Evaluates Program via Perl and returns a Value (PerlBridgeSpec §5).
   *  Returns IO[Either[String, Value]], matching ProgramEval.exec.
   */
  def runViaPerl(program: Program): IO[Either[String, Value]] =
    perlBinary match
      case None => IO.pure(Left(
        s"perl not found (isWindows=$isWindows, perlBin=$perlBin)"
      ))
      case Some(perl) =>
        ProgramLifter.liftTyped(program) match
          case Left(err) => IO.pure(Left(s"liftTyped failed: $err"))
          case Right(typedResult) =>
            val pm = typedResult match
              case ProgramLifter.ScalarTyped(_) => "print_scalar"
              case ProgramLifter.Vec3Typed(_)   => "print_vec3"
            executePerl(toPerlScript(program, pm), perl, "runViaPerl").map:
              case Left(err)  => Left(err)
              case Right(out) => ProgramLifter.parseTypedOutput(out, typedResult)

  /** Returns Perl execution result (PerlBridgeSpec §6).
   *  The caller should compare this with the result of ProgramEval.exec.
   */
  def crossCheck(program: Program): IO[Either[String, Value]] =
    runViaPerl(program)

  // ─── Scala/Perl Cross-Verification Entry Point ────────────────────────────────
  def maybeCheckIO(program: Program, stepName: String): IO[Either[String, Value]] =
    if !sys.env.get("RUN_PERL_CROSSCHECK").contains("1") then
      IO.pure(Left(s"[PerlBridge] SKIP: RUN_PERL_CROSSCHECK not set (step=$stepName)"))
    else
      perlBinary match
        case None =>
          IO.pure(Left(
            s"[PerlBridge] SKIP: perl not found" +
            s" (step=$stepName, os=${System.getProperty("os.name")}, isWindows=$isWindows)"
          ))
        case Some(perl) =>
          ProgramLifter.liftTyped(program) match
            case Left(err) =>
              IO.pure(Left(s"[PerlBridge] SKIP: liftTyped failed: $err"))
            case Right(typedResult) =>
              val printMethod = typedResult match
                case ProgramLifter.ScalarTyped(_) => "print_scalar"
                case ProgramLifter.Vec3Typed(_)   => "print_vec3"
              val script = toPerlScript(program, printMethod)
              executePerl(script, perl, stepName).map:
                case Left(execErr) =>
                  Left(execErr)   // Perl execution error → treated as WARN (no SKIP prefix)

                case Right(output) =>
                  ProgramLifter.parseTypedOutput(output, typedResult) match
                    case Left(parseErr) =>
                      Left(s"Perl parse error: $parseErr (output='$output')")
                    case Right(perlValue) =>
                      // Re-evaluate on the Scala side for comparison
                      // (Independent double-verification separate from StackMachineTasklet evaluation)
                      ProgramEval.exec(program) match
                        case Right(sv) if sv == perlValue =>
                          Right(perlValue)
                        case Right(sv) =>
                          Left(s"MISMATCH: Scala=$sv Perl=$perlValue")
                        case Left(scalaErr) =>
                          Left(s"MISMATCH: Scala=ERROR($scalaErr) Perl=$perlValue")
