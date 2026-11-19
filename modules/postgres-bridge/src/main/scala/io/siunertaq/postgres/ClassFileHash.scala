package io.siunertaq.postgres

// ─── ClassFileHash — a String, opaque-typed so only a valid 32-char hex ────
//     MD5 digest can ever inhabit the type ────────────────────────────────
//
//  Same pattern as io.siunertaq.BSDQuiver's DP.DPState[V <: BSDVertex]:
//  an opaque type over a plain runtime representation (here, String; there,
//  DPStateImpl), constructible only through smart constructors that
//  establish the invariant the type is meant to carry. DPState's invariant
//  is "current vertex matches V"; this type's invariant is "exactly 32 hex
//  characters" -- the shape clickhouse_schema.sql's
//  `class_file_hash FixedString(32) -- MD5 of .class bytes` expects.
//
//  Using SHA-256 here instead (32 raw bytes -> 64 hex chars) would be a
//  *type* error under this scheme, not just a runtime length mismatch --
//  there is no smart constructor that produces a ClassFileHash from a
//  32-byte digest, so it would fail to compile rather than silently
//  produce a value ClickHouse would reject at insert time.
object ClassFileHash:
  opaque type ClassFileHash = String

  /** The only way to construct a ClassFileHash: hash the bytes with MD5.
    * MD5's digest length is fixed at 16 bytes by the algorithm itself, so
    * the hex encoding below is unconditionally 32 characters -- this is
    * not a runtime check, it is a consequence of MD5's definition (see
    * Hash_Encoding.Hex_Length in the Ada/SPARK core for the proved
    * general form of this fact: hex-encoding N bytes yields exactly 2N
    * characters, and 2*16=32).
    */
  def fromMd5(classBytes: Array[Byte]): ClassFileHash =
    val digest = java.security.MessageDigest.getInstance("MD5").digest(classBytes)
    digest.map(b => f"${b & 0xff}%02x").mkString

  extension (h: ClassFileHash)
    /** Unwrap to a plain String for JSON serialization / SQL binding. */
    def value: String = h
    def length: Int = h.length
