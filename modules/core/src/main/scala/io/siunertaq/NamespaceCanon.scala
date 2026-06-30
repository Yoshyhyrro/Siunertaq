package io.siunertaq

// --- NamespaceCanon ---------------------------------------------------------
//
//  Bridges JVM class/method names and Perl package/sub names into a single
//  neutral canonical key, so that ClickHouse can join rows emitted by
//  ClassASTBridge (.class -> JSON) and a future PmASTBridge (.pm -> JSON)
//  on `canonical_name` regardless of which language produced them.
//
//  This generalizes the single-tag-set mapping in BSDVertex.fromTag/toTag
//  to arbitrary class and method names.
//
//  Design choice: rather than a lossless bidirectional JVM <-> Perl name
//  generator (which is inherently ambiguous - "toJson" -> "to_json" is fine,
//  but the reverse "to_json" -> "toJson" vs "toJSON" vs "ToJson" is not
//  uniquely determined), both sides are folded down to one neutral,
//  lowercase, dot-separated key. fromJvm and fromPerl converge on the same
//  string for the corresponding name; suggestPerlPackage / suggestPerlSub
//  are separate, best-effort helpers for generating new Perl code, and are
//  not guaranteed to round-trip.
//
//  Examples:
//    fromJvm("io.siunertaq.expr.Program", "toJson")
//      == fromPerl("Siunertaq::Expr::Program", "to_json")
//      == "expr.program.to_json"
//
//    suggestPerlPackage("io.siunertaq.expr.Program") == "Siunertaq::Expr::Program"
//    suggestPerlSub("toJson")                        == "to_json"

object NamespaceCanon:

  private val JvmRootPrefix  = "io.siunertaq."
  private val PerlRootPrefix = "Siunertaq::"

  /** JVM class name (dot-separated, e.g. "io.siunertaq.expr.Program") and
   *  method name (camelCase, e.g. "toJson") -> canonical cross-language key.
   *
   *  Companion-object suffixes ("$", as emitted for Scala objects/enum cases)
   *  are stripped from the trailing segment only; deeper nested "$Inner$"
   *  mangling is not yet handled (left as a TODO; not encountered in
   *  ClassASTBridge output as of this writing).
   */
  def fromJvm(className: String, methodName: String): String =
    val stripped = className.stripPrefix(JvmRootPrefix).stripSuffix("$")
    val segments = stripped.split('.').filter(_.nonEmpty).map(_.toLowerCase)
    (segments :+ camelToSnake(methodName)).mkString(".")

  /** Perl package name ("::"-separated, e.g. "Siunertaq::Expr::Program") and
   *  sub name (snake_case, e.g. "to_json") -> the same canonical key as the
   *  corresponding fromJvm call.
   */
  def fromPerl(perlPackage: String, subName: String): String =
    val stripped = perlPackage.stripPrefix(PerlRootPrefix)
    val segments = stripped.split("::").filter(_.nonEmpty).map(_.toLowerCase)
    (segments :+ subName.toLowerCase).mkString(".")

  /** Best-effort suggested Perl package name for a JVM class name.
   *  Used when generating new .pm files; not guaranteed reversible. */
  def suggestPerlPackage(className: String): String =
    val stripped = className.stripPrefix(JvmRootPrefix).stripSuffix("$")
    val segments = stripped.split('.').filter(_.nonEmpty).map(capitalize)
    (PerlRootPrefix.stripSuffix("::") +: segments).mkString("::")

  /** Best-effort suggested Perl sub name for a JVM method name.
   *  Idempotent on names that are already snake_case (no uppercase letters),
   *  matching the convention already used in Siunertaq::StackMachine.pm
   *  (execute_json, print_scalar, ...). */
  def suggestPerlSub(methodName: String): String = camelToSnake(methodName)

  // camelCase -> snake_case. Inserts an underscore between a lowercase
  // letter/digit and a following uppercase letter, then lowercases the
  // whole string. No-op on strings that are already snake_case.
  private def camelToSnake(s: String): String =
    s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase

  private def capitalize(s: String): String =
    if s.isEmpty then s else s.head.toUpper.toString + s.tail
