package io.siunertaq.batch

import cats.effect.IO
import io.siunertaq.expr.{Program, ProgramEval, Value}
import io.siunertaq.expr.ProgramLifter

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.util.Comparator

/** Perl (Strawberry / Ubuntu system perl) によるスタックマシン相互検証。
 *
 *  OS 分岐:
 *    Windows  → C:\Strawberry\perl\bin\perl.exe → PATH の perl.exe
 *    Linux 等 → /usr/bin/perl → /usr/local/bin/perl → PATH の perl
 *
 *  活性化条件: 環境変数 RUN_PERL_CROSSCHECK=1 が設定されていること。
 *
 *  中間形式 JSON (Program.toJson):
 *    Scala Program → JSON → Siunertaq::StackMachine->execute_json (Perl)
 *    同じ JSON が ClassASTBridge (.class → JSON) および
 *    ForthRegistrar.registerStep (PostgreSQL JSONB) と共通の中間表現。
 *
 *  スクリプト構成:
 *    $tmpDir/
 *      $label.pl              ← Program.toJson を execute_json に渡す薄いグルー
 *      Siunertaq/
 *        StackMachine.pm      ← classpath リソースから展開 (JSON::PP で実装)
 *
 *  生成スクリプトの永続化 (CI artifact 収集用):
 *    PERL_SCRIPT_SAVE_DIR が設定されていれば .pl と Siunertaq/StackMachine.pm を
 *    保存する。ci-out/perl-scripts/ を指定することで .class と並んで artifact に収まる。
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

  // ─── StackMachine.pm classpath リソース ──────────────────────────────────
  //  batch-bridge JAR の /perl/Siunertaq/StackMachine.pm から読み込む。
  //  初回アクセス時のみロード (lazy + スレッドセーフ)。
  private lazy val stackMachinePm: String =
    val path = "/perl/Siunertaq/StackMachine.pm"
    Option(getClass.getResourceAsStream(path))
      .map { stream =>
        try String(stream.readAllBytes(), StandardCharsets.UTF_8)
        finally stream.close()
      }
      .getOrElse(throw new java.io.FileNotFoundException(
        s"Classpath resource not found: $path — " +
        "StackMachine.pm は batch-bridge/src/main/resources/perl/Siunertaq/ に置いてください"
      ))

  // ─── Program → 薄いグルー .pl ──────────────────────────────────────────────
  //  ロジックは Siunertaq::StackMachine.execute_json (Perl 側) が持つ。
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

  // ─── Perl サブプロセス実行 ─────────────────────────────────────────────────
  //
  //  IO.blocking を使用することで Spring Batch の管理スレッドをブロックしない。
  //
  //  実行ディレクトリ構成:
  //    $tmpDir/
  //      $label.pl              ← .pl スクリプト (FindBin::Bin = $tmpDir)
  //      Siunertaq/
  //        StackMachine.pm      ← classpath から展開; use lib で参照
  //
  //  PERL_SCRIPT_SAVE_DIR が設定されている場合は同じ構成でコピーを保存。
  def executePerl(script: String, perl: String, label: String): IO[Either[String, String]] =
    IO.blocking:
      val tmpDir    = Files.createTempDirectory("siunertaq-perl-")
      val scriptFile = tmpDir.resolve(s"$label.pl")
      val pmDir      = tmpDir.resolve("Siunertaq")
      try
        // .pl を書き出す
        Files.writeString(scriptFile, script)

        // Siunertaq/StackMachine.pm を classpath から展開
        Files.createDirectories(pmDir)
        Files.writeString(pmDir.resolve("StackMachine.pm"), stackMachinePm)

        // CI artifact 収集用: PERL_SCRIPT_SAVE_DIR に .pl と .pm を永続保存
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

        // perl を tmpDir 配下で起動 ($FindBin::Bin = tmpDir)
        val proc = new ProcessBuilder(perl, scriptFile.toString)
          .directory(tmpDir.toFile)
          .redirectErrorStream(true)
          .start()
        val out  = String(proc.getInputStream.readAllBytes()).trim
        val exit = proc.waitFor()
        if exit == 0 then Right(out)
        else Left(s"perl exited $exit: $out")
      finally
        // tmpDir を再帰削除 (最深部から順に削除)
        Files.walk(tmpDir)
          .sorted(Comparator.reverseOrder())
          .forEach(p => Files.deleteIfExists(p): Unit)

  // ─── 公開 API (テスト・外部呼び出し用) ──────────────────────────────────

  /** perl バイナリが見つかるかどうか（テスト・ガード用）。 */
  def isAvailable: Boolean = perlBinary.isDefined

  /** エラーメッセージ用バイナリ名（見つからない場合は "perl"）。 */
  def perlBin: String = perlBinary.getOrElse("perl")

  /** スクリプト生成を外部から確認するための公開ラッパー (PerlBridgeSpec §3)。
   *  printMethod: "print_scalar" | "print_vec3"
   */
  def generatePerl(program: Program, printMethod: String): String =
    toPerlScript(program, printMethod)

  /** Program を Perl で評価し Value を返す (PerlBridgeSpec §5)。
   *  ProgramEval.exec と同じ型 Either[String, Value] を IO に包む。
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

  /** Perl 実行結果を返す (PerlBridgeSpec §6)。
   *  呼び出し側で ProgramEval.exec 結果と比較する。
   */
  def crossCheck(program: Program): IO[Either[String, Value]] =
    runViaPerl(program)

  // ─── Scala/Perl 相互検証 エントリポイント ────────────────────────────────
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