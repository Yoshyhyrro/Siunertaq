package io.siunertaq.batch

import cats.effect.IO
import io.siunertaq.expr.{Instr, Program, ProgramEval, Value}
import io.siunertaq.expr.ProgramLifter

import java.nio.file.{Files, Paths, StandardCopyOption}

/** 
 * Cross-validation harness for the stack machine using Perl (Strawberry / Ubuntu system perl).
 *
 * OS-specific binary resolution:
 *   - Windows: C:\Strawberry\perl\bin\perl.exe -> PATH-based perl.exe
 *   - Linux/etc: /usr/bin/perl -> /usr/local/bin/perl -> PATH-based perl
 *
 * Activation: Enabled only if environment variable `RUN_PERL_CROSSCHECK=1` is set.
 *
 * Artifact Persistence (for CI):
 *   If `PERL_SCRIPT_SAVE_DIR` is defined, the executed .pl script is persisted to 
 *   `$PERL_SCRIPT_SAVE_DIR/$stepName.pl` before deletion. This allows CI to collect 
 *   scripts as artifacts alongside .class files (e.g., via ci-out/perl-scripts/).
 *
 * Semantics of `maybeCheckIO` return value:
 *   - Right(v)                     => Success: Scala and Perl results match.
 *   - Left("[PerlBridge] SKIP: ...") => Ignored: Cross-check disabled, perl not found, or lift failed.
 *   - Left("MISMATCH: ...")        => Critical: Value divergence. StepExecutorActor treats this as FAILED.
 *   - Left(Other)                  => Error: Perl execution or parsing failure. Treated as WARN.
 */
object PerlBridge:

  // --- OS Detection ---------------------------------------------------------
  private val isWindows: Boolean =
    System.getProperty("os.name", "").toLowerCase.contains("win")

  // Resolve executable binaries from PATH (filtering out empty directories)
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

  /** Full path to the perl binary (lazy-evaluated and thread-safe).
   *
   * Standard paths:
   *   - Ubuntu 26: /usr/bin/perl (default)
   *   - Strawberry Perl: C:\Strawberry\perl\bin\perl.exe
   */
  lazy val perlBinary: Option[String] =
    if isWindows then
      // Prioritize Strawberry Perl's default installation path
      List(
        raw"C:\Strawberry\perl\bin\perl.exe",
        raw"C:\strawberry\perl\bin\perl.exe"
      ).find(p => Files.isExecutable(Paths.get(p)))
        .orElse(findInPath("perl.exe", "perl"))
    else
      // Linux/macOS: Prioritize system perl
      List("/usr/bin/perl", "/usr/local/bin/perl")
        .find(p => Files.isExecutable(Paths.get(p)))
        .orElse(findInPath("perl"))

  // --- Program -> Perl Script Transpilation ----------------------------------
  //
  // Transpiles stack machine instructions into equivalent Perl code.
  //   Scalar: Push integer onto @stack
  //   Vec3  : Push arrayref [x, y, z] onto @stack
  //
  // Note: Perl's @stack[-1] represents the Top of Stack (TOS).
  //       `pop @stack` follows LIFO order, consistent with Scala's ArrayStack.pop.
  private def toPerlScript(program: Program, printExpr: String): String =
    val sb = new StringBuilder(
      "#!/usr/bin/perl\nuse strict;\nuse warnings;\nmy @stack;\n"
    )
    program.foreach:
      case Instr.PushScalar(n) =>
        sb ++= s"push @stack, $n;\n"

      case Instr.PushVec3(x, y, z) =>
        sb ++= s"push @stack, [$x, $y, $z];\n"

      case Instr.AddScalar =>
        // Pop order: r = top, l = next (inverse of Lowering push order)
        sb ++= "{ my $r=pop @stack; my $l=pop @stack; push @stack, $l+$r; }\n"

      case Instr.AddVec3 =>
        sb ++= "{ my $r=pop @stack; my $l=pop @stack; " +
               "push @stack, [$l->[0]+$r->[0],$l->[1]+$r->[1],$l->[2]+$r->[2]]; }\n"

      case Instr.MulScalar =>
        sb ++= "{ my $r=pop @stack; my $l=pop @stack; push @stack, $l*$r; }\n"

      case Instr.DotVec3 =>
        sb ++= "{ my $r=pop @stack; my $l=pop @stack; " +
               "push @stack, $l->[0]*$r->[0]+$l->[1]*$r->[1]+$l->[2]*$r->[2]; }\n"

    sb ++= printExpr
    sb += '\n'
    sb.toString

  // --- Perl Subprocess Execution ---------------------------------------------
  //
  // Wrapped in `IO.blocking` to prevent starving the Spring Batch management threads.
  // We use temporary files instead of stdin pipes to avoid known stdin buffering 
  // issues encountered with Strawberry Perl.
  //
  // If `PERL_SCRIPT_SAVE_DIR` is set, the script is persisted as `$PERL_SCRIPT_SAVE_DIR/$label.pl`
  // before execution, allowing it to be collected as a CI artifact.
  def executePerl(script: String, perl: String, label: String): IO[Either[String, String]] =
    IO.blocking:
      val tmp = Files.createTempFile("siunertaq-perl-", ".pl")
      try
        Files.writeString(tmp, script)

        // Persist for CI artifact collection
        sys.env.get("PERL_SCRIPT_SAVE_DIR").foreach { dir =>
          val dest = Paths.get(dir).resolve(s"$label.pl")
          Files.createDirectories(dest.getParent)
          Files.copy(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        }

        val proc = new ProcessBuilder(perl, tmp.toString)
          .redirectErrorStream(true)   // Merge stderr into stdout
          .start()
        val out  = String(proc.getInputStream.readAllBytes()).trim
        val exit = proc.waitFor()
        if exit == 0 then Right(out)
        else Left(s"perl exited $exit: $out")
      finally
        Files.deleteIfExists(tmp): Unit

  // --- Scala/Perl Cross-Validation Entry Point --------------------------------
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
              val script = toPerlScript(program, ProgramLifter.perlPrintFor(typedResult))
              executePerl(script, perl, stepName).map:
                case Left(execErr) =>
                  Left(execErr)   // Execution error -> Treated as WARN (no SKIP prefix)

                case Right(output) =>
                  ProgramLifter.parseTypedOutput(output, typedResult) match
                    case Left(parseErr) =>
                      Left(s"Perl parse error: $parseErr (output='$output')")
                    case Right(perlValue) =>
                      // Re-evaluate in Scala to ensure double-verification 
                      // independent of the primary StackMachineTasklet execution.
                      ProgramEval.exec(program) match
                        case Right(sv) if sv == perlValue =>
                          Right(perlValue)
                        case Right(sv) =>
                          Left(s"MISMATCH: Scala=$sv Perl=$perlValue")
                        case Left(scalaErr) =>
                          Left(s"MISMATCH: Scala=ERROR($scalaErr) Perl=$perlValue")