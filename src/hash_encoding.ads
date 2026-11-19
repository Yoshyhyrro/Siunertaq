package Hash_Encoding with SPARK_Mode is

   --  The relationship this package exists to prove: hex-encoding N raw
   --  bytes (two hex digits per byte) always yields exactly 2*N
   --  characters. This is the fact that justifies MD5 (a fixed 16-byte
   --  digest) as the right choice for clickhouse_schema.sql's
   --  `class_file_hash FixedString(32)` column, and rules out SHA-256
   --  (a fixed 32-byte digest, hex-encoding to 64 characters) --
   --  mirrors io.siunertaq.postgres.ClassFileHash on the Scala side,
   --  which enforces the same fact at the type level via an opaque type
   --  constructible only through a from-MD5 smart constructor.

   subtype Digest_Byte_Count is Natural range 0 .. 128;
   --  128 is generous headroom (covers every common digest algorithm;
   --  MD5=16, SHA-1=20, SHA-256=32, SHA-512=64) while keeping the
   --  multiplication below provably free of overflow for any subtype
   --  member, without needing a case-by-case bound.

   function Hex_Length (Byte_Count : Digest_Byte_Count) return Natural is
     (Byte_Count * 2)
   with
     Post => Hex_Length'Result = Byte_Count * 2;

   MD5_Digest_Bytes    : constant Digest_Byte_Count := 16;
   SHA256_Digest_Bytes : constant Digest_Byte_Count := 32;

   Schema_Class_File_Hash_Width : constant := 32;
   --  Mirrors clickhouse_schema.sql's `class_file_hash FixedString(32)`.
   --  Kept as a literal (not derived from Hex_Length) so a change to
   --  either side is a visible diff, not a silent tautology; the two
   --  lemmas below are what keep them honest against each other.

   function MD5_Matches_Schema_Width return Boolean is
     (Hex_Length (MD5_Digest_Bytes) = Schema_Class_File_Hash_Width)
   with
     Post => MD5_Matches_Schema_Width'Result;
   --  PROOF TARGET: this Post is a claim that MD5's hex length equals the
   --  schema's declared width -- provable outright, since both sides are
   --  static (16*2 = 32), not just consistent under some assumption.

   function SHA256_Matches_Schema_Width return Boolean is
     (Hex_Length (SHA256_Digest_Bytes) = Schema_Class_File_Hash_Width)
   with
     Post => SHA256_Matches_Schema_Width'Result = False;
   --  PROOF TARGET: the mirror-image claim -- SHA-256 does NOT match
   --  (32*2=64 /= 32). Stated as its own function (rather than just
   --  negating the MD5 one) so gnatprove discharges each width
   --  independently: this is what caught the SHA-256 mismatch in the
   --  ported ClassASTBridge.scala, restated here as something proved
   --  once rather than re-derived by inspection each time.

end Hash_Encoding;
