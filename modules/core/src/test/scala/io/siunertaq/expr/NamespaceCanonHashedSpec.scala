package io.siunertaq

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class NamespaceCanonHashedSpec extends AnyFunSuite with Matchers {

  test("normalizeEncoding resolves NFD and NFC to identical byte sequences") {
    // Validate equivalence between precomposed characters (NFC) and combining character sequences (NFD)
    val precomposed = "café"
    val decomposed = "cafe\u0301"

    precomposed should not equal decomposed
    NamespaceCanonHashed.normalizeEncoding.apply(precomposed) shouldEqual 
      NamespaceCanonHashed.normalizeEncoding.apply(decomposed)
  }

  test("normalizeEncoding handles standard ASCII strings without mutation") {
    // Ensure standard alphanumeric strings remain unaffected during the NFC transformation
    val standardAscii = "ClassASTBridge"
    
    NamespaceCanonHashed.normalizeEncoding.apply(standardAscii) shouldEqual standardAscii
  }

  test("toMd5Hex computes correct 32-character hex digest") {
    val input = "siunertaq_bridge"
    val result = NamespaceCanonHashed.toMd5Hex.apply(input)

    // Assert invariant: MD5 digest length must strictly be 32 hexadecimal characters
    result.length shouldEqual 32
    result should fullyMatch regex "^[a-f0-9]{32}$"
  }

  test("canonicalizeAndHash produces identical digests regardless of initial normalization form") {
    val nfcString = "Åström"
    val nfdString = "A\u030Astro\u0308m"

    val hash1 = NamespaceCanonHashed.canonicalizeAndHash.apply(nfcString)
    val hash2 = NamespaceCanonHashed.canonicalizeAndHash.apply(nfdString)

    // Verify collision resistance against visual equivalence discrepancies across language boundaries
    hash1 shouldEqual hash2
  }
  
  test("canonicalizeAndHash maintains deterministic output for identical repeated invocations") {
    val input = "deterministic_execution_test"
    
    val hash1 = NamespaceCanonHashed.canonicalizeAndHash.apply(input)
    val hash2 = NamespaceCanonHashed.canonicalizeAndHash.apply(input)
    
    hash1 shouldEqual hash2
  }
}