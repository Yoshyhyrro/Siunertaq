package io.siunertaq

import java.util.function.Function
import java.text.Normalizer
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

// ─── NamespaceCanon.Hashed — encoding-safe fixed-width fingerprint ────────
//
//  fromJvm/fromPerl already fold JVM and Perl names down to the same
//  canonical string *when both sides see the identical sequence of
//  characters*. What they don't handle: className/methodName arrive from
//  ClassASTBridge as JVM-internal strings, while perlPackage/subName will
//  arrive from a future PmASTBridge via .pm source text -- if the .pm file
//  or its reader ever disagrees with the JVM side on Unicode normalization
//  form or byte encoding, two *visually identical* names can produce two
//  *different* Java Strings, and fromJvm/fromPerl would silently diverge.
//
//  Hashing does NOT fix that by itself: MD5(a) == MD5(b) only if a and b
//  are already the same byte sequence. The fix is normalizing *before*
//  hashing (normalizeEncoding below); MD5 afterward is purely a
//  convenience -- a fixed-width (32 hex char) fingerprint, chained on
//  with Function.andThen(), matching how MD5 is used elsewhere in this
//  project (ClassFileHash) as an ordinary hash algorithm, not for any
//  cryptographic property.
object NamespaceCanonHashed:

  /** NFC-normalize, then round-trip through UTF-8 bytes. Two Strings that
    * are visually the same name but arrived via different Unicode
    * composition (e.g. precomposed vs. combining-character sequences) or
    * were decoded with different source encodings collapse to the same
    * result here, *before* anything downstream compares or hashes them. */
  val normalizeEncoding: Function[String, String] =
    (s: String) =>
      new String(
        Normalizer.normalize(s, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8
      )

  /** MD5 hex digest of a String's UTF-8 bytes. An ordinary fixed-width
    * hash, chosen for convenience (matches ClassFileHash elsewhere in
    * this project) -- not chosen for, or relied on for, any
    * cryptographic strength. */
  val toMd5Hex: Function[String, String] =
    (s: String) =>
      val digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8))
      digest.map(b => f"${b & 0xff}%02x").mkString

  /** normalizeEncoding andThen toMd5Hex: the chain that actually makes a
    * fromJvm/fromPerl canonical_name safe to compare/join on even when
    * the two producers disagree on encoding upstream. */
  val canonicalizeAndHash: Function[String, String] =
    normalizeEncoding.andThen(toMd5Hex)

  /** fromJvm's canonical string, run through the encoding-safe hash chain. */
  def fromJvmHashed(className: String, methodName: String): String =
    canonicalizeAndHash.apply(NamespaceCanon.fromJvm(className, methodName))

  /** fromPerl's canonical string, run through the same chain -- agrees
    * with fromJvmHashed for the corresponding name even if this string
    * arrived via a different source encoding than the JVM side did. */
  def fromPerlHashed(perlPackage: String, subName: String): String =
    canonicalizeAndHash.apply(NamespaceCanon.fromPerl(perlPackage, subName))