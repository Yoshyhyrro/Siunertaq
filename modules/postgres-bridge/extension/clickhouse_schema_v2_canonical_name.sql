-- clickhouse_schema_v2_canonical_name.sql
-- Migration: add canonical_name to bytecode_instructions / forth_words,
--            and create cross_language_equivalences VIEW.
--
-- Run after clickhouse_schema.sql:
--   clickhouse-client --multiline < clickhouse_schema_v2_canonical_name.sql
--
-- canonical_name format (mirrors NamespaceCanon.fromJvm / fromPerl in core):
--   "expr.program.to_json"
--   "batch.perlbridge.generate_perl"
--   "postgres.classastbridge.extract_from_bytes"
--
--   Algorithm:
--     1. Strip "io.siunertaq." prefix from class_name (JVM side)
--        OR strip "Siunertaq::" prefix from pm_namespace and replace "::" with "."
--     2. Lowercase all segments
--     3. camelCase method_name -> snake_case  (([a-z0-9])([A-Z]) -> $1_$2, lowercase)
--     4. Join with "."
--
-- The Scala implementation (io.siunertaq.NamespaceCanon) is authoritative.
-- This SQL expression is a faithful translation of that logic for in-database use.

USE siunertaq;

-- ── §1  bytecode_instructions: add canonical_name ───────────────────────────

ALTER TABLE bytecode_instructions
    ADD COLUMN IF NOT EXISTS canonical_name LowCardinality(String) DEFAULT '';

-- Backfill existing rows.
-- replaceRegexpAll covers the camelCase -> snake_case rule:
--   ([a-z0-9])([A-Z]) -> $1_$2, then lower() for the whole string.
-- arrayStringConcat + splitByChar reconstruct the dot-delimited key from the
-- stripped class_name segments plus the transformed method_name.
ALTER TABLE bytecode_instructions
    UPDATE canonical_name =
        concat(
            lower(replaceRegexpAll(
                replaceAll(class_name, 'io.siunertaq.', ''),
                '\\.', '.'
            )),
            '.',
            lower(replaceRegexpAll(method_name, '([a-z0-9])([A-Z])', '\\1_\\2'))
        )
    WHERE canonical_name = '';


-- ── §2  forth_words: add canonical_name ─────────────────────────────────────

ALTER TABLE forth_words
    ADD COLUMN IF NOT EXISTS canonical_name LowCardinality(String) DEFAULT '';

ALTER TABLE forth_words
    ADD COLUMN IF NOT EXISTS language LowCardinality(String) DEFAULT 'jvm';

ALTER TABLE forth_words
    UPDATE canonical_name =
        concat(
            lower(replaceRegexpAll(
                replaceAll(class_name, 'io.siunertaq.', ''),
                '\\.', '.'
            )),
            '.',
            lower(replaceRegexpAll(method_name, '([a-z0-9])([A-Z])', '\\1_\\2'))
        )
    WHERE canonical_name = '';


-- ── §3  cross_language_equivalences VIEW ─────────────────────────────────────
--
--  Shows canonical names for which both a JVM implementation (.class via
--  ClassASTBridge / MecrispCompiler) and a Perl implementation (.pm via
--  the future PmASTBridge) exist in ClickHouse.
--
--  Populated by:
--    JVM side:  ClickHouseSync.flushBytecode / flushWords
--               (NamespaceCanon.fromJvm computed in Scala, written here)
--    Perl side: PmASTBridge (not yet implemented; rows land in forth_words
--               with language = 'perl' once that bridge exists)
--
--  Until PmASTBridge exists, this VIEW will only show JVM rows; the
--  HAVING filter ensures it only surfaces rows present in both languages,
--  so false positives never appear.

CREATE OR REPLACE VIEW cross_language_equivalences AS
SELECT
    canonical_name,
    groupArray(tuple(language, instructions_fingerprint)) AS implementations,
    countIf(language = 'jvm')  AS jvm_count,
    countIf(language = 'perl') AS perl_count,
    minIf(compiled_at, language = 'jvm')  AS jvm_first_seen,
    minIf(compiled_at, language = 'perl') AS perl_first_seen
FROM (
    -- JVM side: one row per (class, method) from bytecode_instructions
    SELECT
        canonical_name,
        'jvm'                                  AS language,
        -- fingerprint: sorted opcode sequence as a compact string for quick diff
        arrayStringConcat(
            arraySort(groupArray(opcode_name)),
            ','
        )                                      AS instructions_fingerprint,
        min(ingested_at)                       AS compiled_at
    FROM bytecode_instructions
    WHERE canonical_name != ''
    GROUP BY canonical_name

    UNION ALL

    -- Perl side: one row per word from forth_words where language tag = 'perl'
    -- (forth_words does not yet have a language column; this anticipates
    --  PmASTBridge adding rows with a 'perl' marker)
    SELECT
        canonical_name,
        'perl'                                 AS language,
        arrayStringConcat(arraySort(body_tokens), ',') AS instructions_fingerprint,
        min(compiled_at)                       AS compiled_at
    FROM forth_words
    WHERE canonical_name != ''
      AND language = 'perl'   -- filtered to Perl-origin rows only
    GROUP BY canonical_name
)
GROUP BY canonical_name
HAVING jvm_count > 0 AND perl_count > 0;


-- ── §4  opcode_frequency_mv: include canonical_name ─────────────────────────
--
--  The existing opcode_frequency_mv materialized view (if already created)
--  does not carry canonical_name. Rather than ALTER the MV (which requires
--  DROP + re-CREATE in ClickHouse), this adds a companion summary table
--  that groups by canonical_name for the cross-language analytics use case.

CREATE TABLE IF NOT EXISTS canonical_opcode_frequency
(
    canonical_name  LowCardinality(String),
    opcode_name     LowCardinality(String),
    freq            UInt64
)
ENGINE = SummingMergeTree()
ORDER BY (canonical_name, opcode_name);

CREATE MATERIALIZED VIEW IF NOT EXISTS canonical_opcode_frequency_mv
TO canonical_opcode_frequency AS
SELECT
    canonical_name,
    opcode_name,
    count() AS freq
FROM bytecode_instructions
WHERE canonical_name != ''
GROUP BY canonical_name, opcode_name;
