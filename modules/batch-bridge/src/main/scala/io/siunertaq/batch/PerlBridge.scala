package io.siunertaq.batch

import cats.effect.IO
import io.siunertaq.expr.{Instr, Program, ProgramEval, Value}
import io.siunertaq.expr.ProgramLifter

import java.nio.file.{Files, Paths}

/** Perl (Strawberry / Ubuntu system perl) によるスタックマシン相互検証。
 *
 *  OS 分岐:
 *    Windows  → C:\Strawberry\perl\bin\perl.exe → PATH の perl.exe
 *    Linux 等 → /usr/bin/perl → /usr/local/bin/perl → PATH の perl
 *
 *  活性化条件: 環境変数 RUN_PERL_CROSSCHECK=1 が設定されていること。
 *
 *  maybeCheckIO 戻り値セマンティクス:
 *    Right(v)                     → Scala と Perl が一致、v は共通値
 *    Left("[PerlBridge] SKIP: …") → クロスチェック無効 / perl 未検出 / lift失敗
 *    Left("MISMATCH: …")          → 値が食い違う → StepExecutorActor が FAILED 扱い
 *    Left(その他)                  → Perl 実行 / パースエラー → WARN 扱い
 */
object PerlBridge:

  // ─── OS 判定 ──────────────────────────────────────────────────────────────
  private val isWindows: Boolean =
    System.getProperty("os.name", "").toLowerCase.contains("win")

  // PATH から実行可能バイナリを探す (空ディレクトリ除外)
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

  /** perl バイナリのフルパス (遅延評価・スレッドセーフ)。
   *
   *  Ubuntu 26 では /usr/bin/perl がデフォルト。
   *  Strawberry Perl は C:\Strawberry\perl\bin\perl.exe が標準インストール先。
   */
  lazy val perlBinary: Option[String] =
    if isWindows then
      // Strawberry Perl の固定パスを優先
      List(
        raw"C:\Strawberry\perl\bin\perl.exe",
        raw"C:\strawberry\perl\bin\perl.exe"
      ).find(p => Files.isExecutable(Paths.get(p)))
        .orElse(findInPath("perl.exe", "perl"))
    else
      // Ubuntu / Debian / macOS 共通: システム perl を優先
      List("/usr/bin/perl", "/usr/local/bin/perl")
        .find(p => Files.isExecutable(Paths.get(p)))
        .orElse(findInPath("perl"))

  // ─── Program → Perl スクリプト生成 ────────────────────────────────────────
  //
  //  スタックマシン命令を等価な Perl コードに変換する。
  //    Scalar: @stack に整数をプッシュ
  //    Vec3  : @stack に arrayref [x, y, z] をプッシュ
  //
  //  注: Perl の @stack[-1] = TOS (top of stack)
  //      pop @stack = pop — Scala の ArrayStack.pop と同じ LIFO 順序。
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
        // pop 順: r = top, l = next (Lowering の push 順と逆)
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

  // ─── Perl サブプロセス実行 ─────────────────────────────────────────────────
  //
  //  IO.blocking を使用することで Spring Batch の管理スレッドをブロックしない。
  //  一時ファイルを作成して perl に渡す (stdin パイプではなくファイル渡しにする
  //  のは Strawberry Perl の stdin バッファリング問題を回避するため)。
  def executePerl(script: String, perl: String): IO[Either[String, String]] =
    IO.blocking:
      val tmp = Files.createTempFile("siunertaq-perl-", ".pl")
      try
        Files.writeString(tmp, script)
        val proc = new ProcessBuilder(perl, tmp.toString)
          .redirectErrorStream(true)   // stderr を stdout にマージ
          .start()
        val out  = String(proc.getInputStream.readAllBytes()).trim
        val exit = proc.waitFor()
        if exit == 0 then Right(out)
        else Left(s"perl exited $exit: $out")
      finally
        Files.deleteIfExists(tmp)

  // ─── Scala/Perl 相互検証 エントリポイント ────────────────────────────────
  def maybeCheckIO(program: Program, stepName: String): IO[Either[String, Value]] =
    if !sys.env.get("RUN_PERL_CROSSCHECK").contains("1") then
      IO.pure(Left("[PerlBridge] SKIP: RUN_PERL_CROSSCHECK not set"))
    else
      perlBinary match
        case None =>
          IO.pure(Left(
            s"[PerlBridge] SKIP: perl not found" +
            s" (os=${System.getProperty("os.name")}, isWindows=$isWindows)"
          ))
        case Some(perl) =>
          ProgramLifter.liftTyped(program) match
            case Left(err) =>
              IO.pure(Left(s"[PerlBridge] SKIP: liftTyped failed: $err"))
            case Right(typedResult) =>
              val script = toPerlScript(program, ProgramLifter.perlPrintFor(typedResult))
              executePerl(script, perl).map:
                case Left(execErr) =>
                  Left(execErr)   // Perl 実行エラー → WARN (SKIP プレフィックスなし)

                case Right(output) =>
                  ProgramLifter.parseTypedOutput(output, typedResult) match
                    case Left(parseErr) =>
                      Left(s"Perl parse error: $parseErr (output='$output')")
                    case Right(perlValue) =>
                      // Scala 側を再評価して比較
                      // (StackMachineTasklet での評価と独立した二重検証)
                      ProgramEval.exec(program) match
                        case Right(sv) if sv == perlValue =>
                          Right(perlValue)
                        case Right(sv) =>
                          Left(s"MISMATCH: Scala=$sv Perl=$perlValue")
                        case Left(scalaErr) =>
                          Left(s"MISMATCH: Scala=ERROR($scalaErr) Perl=$perlValue")
