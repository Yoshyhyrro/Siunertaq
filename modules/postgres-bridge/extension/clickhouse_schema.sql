-- ============================================================
-- ClickHouse schema for Siunertaq postgres-bridge analytics
-- Target: ClickHouse 24.x (LTS)
-- CDC source: PostgreSQL via MaterializedPostgreSQL or Debezium
--
-- Architecture:
--   PostgreSQL (operational)
--     mzv_triple_log        → ch: mzv_triple_stream     (CDC)
--     compiled_words        → ch: compiled_words_mirror (CDC)
--   Scala ClassASTBridge (direct write, high volume)
--     → ch: bytecode_instructions  (billions of rows)
--     → ch: forth_words            (compiled Mecrisp words)
--
-- Run: clickhouse-client --multiline < clickhouse_schema.sql
-- ============================================================

-- ── §0  Database ────────────────────────────────────────────────────────────

CREATE DATABASE IF NOT EXISTS siunertaq;
USE siunertaq;


-- ── §1  CDC mirror from PostgreSQL ──────────────────────────────────────────
--
--  Option A (recommended): MaterializedPostgreSQL engine
--    Requires: PostgreSQL logical replication enabled
--      postgresql.conf: wal_level = logical
--      pg_hba.conf: replication permission for the user
--
--  Uncomment to activate:
-- CREATE DATABASE siunertaq_pg_replica
-- ENGINE = MaterializedPostgreSQL('localhost:5432', 'siunertaq', 'pguser', 'pgpass')
-- SETTINGS materialized_postgresql_tables_list = 'mzv_triple_log,compiled_words';
--
--  Option B (current): batch push from Scala ClickHouseSync actor
--  The tables below are the targets for that push.


-- ── §2  Core analytics table: JVM bytecode instructions ─────────────────────
--
--  Expected scale: 100M–10B rows (all classes in a large JVM application)
--  MergeTree with ReplacingMergeTree deduplication on (class, method, idx)
--
--  LowCardinality: class_name/method_name are repeated ~1000x per method
--  → stored as dict encoding; dramatic compression + faster GROUP BY

CREATE TABLE IF NOT EXISTS bytecode_instructions
(
    -- Source coordinates
    class_name          LowCardinality(String),
    method_name         LowCardinality(String),
    method_descriptor   String,
    instruction_idx     UInt32,                         -- position in method body

    -- JVM opcode
    opcode              UInt16,
    opcode_name         LowCardinality(String),         -- e.g. "IADD", "BIPUSH"
    operand_int         Nullable(Int32),                -- BIPUSH/SIPUSH/ISTORE operand
    operand_str         Nullable(String),               -- LDC string / type descriptor

    -- Mecrisp-Stellaris compiled form
    mecrisp_tokens      Array(String),                  -- e.g. ["3", "+", "dup"]
    mecrisp_word_name   LowCardinality(String),         -- compiled Forth word (if word boundary)
    stack_depth_before  Int8,                           -- stack depth entering this instruction
    stack_depth_after   Int8,                           -- stack depth leaving

    -- Dead code analysis
    is_reachable        UInt8 DEFAULT 1,                -- 0 = dead code (unreachable)
    is_after_return     UInt8 DEFAULT 0,                -- 1 = after IRETURN/RETURN in same block
    is_unreferenced     UInt8 DEFAULT 0,                -- 1 = method never invoked (call graph)

    -- Ingestion metadata
    class_file_hash     FixedString(32),                -- MD5 of .class bytes (for dedup)
    ingested_at         DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMMDD(ingested_at)
ORDER BY (class_name, method_name, instruction_idx)
SETTINGS index_granularity = 8192;


-- ── §3  Compiled Forth word definitions ──────────────────────────────────────
--
--  One row per compiled JVM method → Mecrisp word.
--  body_tokens and called_words use ClickHouse Array for n-gram / set ops.

CREATE TABLE IF NOT EXISTS forth_words
(
    word_name           String,                          -- Mecrisp word name (derived from class.method)
    class_name          LowCardinality(String),
    method_name         LowCardinality(String),
    method_descriptor   String,
    stack_effect        String,                          -- "( a b -- c )" computed from analysis

    -- Instruction sequence as flat token array
    body_tokens         Array(String),                   -- ["3", "1", "*", "dup", "+"]
    called_words        Array(String),                   -- words this word calls (CALL instructions)
    literal_constants   Array(Int64),                    -- integer literals pushed in this word

    -- Stack analytics
    max_stack_depth     UInt8,
    min_stack_depth     Int8,                            -- can go negative (bug indicator)
    instruction_count   UInt32,

    -- Dead code flags
    has_dead_code       UInt8 DEFAULT 0,                 -- any is_reachable=0 in its instructions
    has_infinite_loop   UInt8 DEFAULT 0,                 -- begin...again with no exit
    is_leaf_word        UInt8 DEFAULT 1,                 -- calls no other Forth words

    -- Source
    class_file_hash     FixedString(32),
    compiled_at         DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(compiled_at)
ORDER BY (class_name, method_name, word_name)
SETTINGS index_granularity = 8192;


-- ── §4  CDC mirror: mzv_triple_log (from PostgreSQL) ────────────────────────

CREATE TABLE IF NOT EXISTS mzv_triple_stream
(
    log_id              UInt64,
    s1                  Int32,
    s2                  Int32,
    s3                  Int32,
    mzv_weight          Int32 MATERIALIZED s1 + s2 + s3,
    is_convergent       UInt8,
    src_sector          LowCardinality(String),          -- "Outer" | "Inner"
    src_phase           UInt8,
    tgt_sector          LowCardinality(String),
    tgt_phase           UInt8,
    pentagon_delta      Int8 MATERIALIZED
        if(src_sector != tgt_sector, 1, -1),
    was_regularized     UInt8,
    original_s1         Nullable(Int32),
    compiled_step       Nullable(String),
    logged_at           DateTime
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(logged_at)
ORDER BY (log_id, logged_at)
SETTINGS index_granularity = 8192;


-- ── §5  CDC mirror: compiled_words (from PostgreSQL siunertaq_forth) ─────────

CREATE TABLE IF NOT EXISTS compiled_words_mirror
(
    step_name           String,
    job_name            LowCardinality(String),
    effect_tag          LowCardinality(String),
    target_vertex       LowCardinality(String),
    priority            UInt8,
    instructions_json   String,                          -- raw JSON from PostgreSQL
    compiled_at         DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(compiled_at)
ORDER BY (job_name, step_name)
SETTINGS index_granularity = 8192;


-- ── §6  Analytical views ─────────────────────────────────────────────────────

-- §6a  Dead word detection: defined but never called
CREATE VIEW IF NOT EXISTS dead_forth_words AS
SELECT
    fw.word_name,
    fw.class_name,
    fw.method_name,
    fw.stack_effect,
    fw.instruction_count,
    fw.compiled_at
FROM forth_words AS fw
WHERE fw.word_name NOT IN (
    SELECT DISTINCT arrayJoin(called_words)
    FROM forth_words
    WHERE length(called_words) > 0
)
AND fw.word_name != 'main'  -- entry points are not dead by convention
ORDER BY fw.instruction_count DESC;


-- §6b  Hot instruction trigrams (n=3 sliding window, array-based)
--  Finds the most common 3-token sequences across all compiled words.
--  ClickHouse Array + arraySlice makes this trivial at scale.
CREATE VIEW IF NOT EXISTS hot_trigrams AS
SELECT
    arrayStringConcat(arraySlice(body_tokens, toUInt64(i), 3), ' ') AS trigram,
    count()                                                            AS frequency,
    uniqExact(word_name)                                               AS word_count
FROM forth_words
ARRAY JOIN arrayEnumerate(body_tokens) AS i
WHERE toUInt64(i) + 2 <= length(body_tokens)
    AND length(body_tokens) >= 3
GROUP BY trigram
ORDER BY frequency DESC
LIMIT 100;


-- §6c  Stack depth histogram across all instructions
CREATE VIEW IF NOT EXISTS stack_depth_distribution AS
SELECT
    stack_depth_before AS depth,
    count()            AS instruction_count,
    uniqExact(class_name || '.' || method_name) AS method_count
FROM bytecode_instructions
WHERE is_reachable = 1
GROUP BY depth
ORDER BY depth;


-- §6d  Dead code report: unreachable instructions per class
CREATE VIEW IF NOT EXISTS dead_code_by_class AS
SELECT
    class_name,
    countIf(is_reachable = 0)      AS dead_instruction_count,
    countIf(is_after_return = 1)   AS after_return_count,
    countIf(is_unreferenced = 1)   AS unreferenced_method_count,
    count()                         AS total_instruction_count,
    round(countIf(is_reachable = 0) * 100.0 / count(), 2) AS dead_pct
FROM bytecode_instructions
GROUP BY class_name
ORDER BY dead_instruction_count DESC;


-- §6e  MZV traversal analytics: weight distribution
CREATE VIEW IF NOT EXISTS mzv_weight_stats AS
SELECT
    mzv_weight,
    count()                         AS traversal_count,
    countIf(is_convergent = 1)      AS convergent_count,
    countIf(was_regularized = 1)    AS regularized_count,
    countIf(src_sector = 'Outer' AND tgt_sector = 'Inner') AS real_to_imag,
    countIf(src_sector = 'Inner' AND tgt_sector = 'Outer') AS imag_to_real,
    avg(pentagon_delta)             AS avg_delta
FROM mzv_triple_stream
GROUP BY mzv_weight
ORDER BY mzv_weight;


-- §6f  Fixed-point detector: triples that return to themselves after traversal
--  P4 (SAT) proved MZVTriple(3,2,1) is a fixed point of path 0→5→8.
--  This view finds all such fixed points in the logged data.
CREATE VIEW IF NOT EXISTS fixed_point_triples AS
SELECT
    a.log_id AS entry_id,
    a.s1, a.s2, a.s3,
    a.mzv_weight,
    a.src_sector, a.src_phase,
    b.tgt_sector, b.tgt_phase
FROM mzv_triple_stream AS a
INNER JOIN mzv_triple_stream AS b
    ON  a.s1 = b.s1
    AND a.s2 = b.s2
    AND a.s3 = b.s3
    AND a.log_id < b.log_id
    AND a.src_sector = b.tgt_sector
    AND a.src_phase  = b.tgt_phase
ORDER BY a.s1 + a.s2 + a.s3;


-- ── §7  Materialized view: opcode frequency (updated incrementally) ──────────
--
--  ClickHouse materializes this incrementally as new rows arrive.
--  No separate job needed — ClickHouse handles the merge.

CREATE MATERIALIZED VIEW IF NOT EXISTS opcode_frequency_mv
ENGINE = SummingMergeTree()
ORDER BY (class_name, opcode_name)
AS
SELECT
    class_name,
    opcode_name,
    count() AS cnt
FROM bytecode_instructions
GROUP BY class_name, opcode_name;


-- ── §8  Kafka integration (optional: Debezium CDC path) ──────────────────────
--
--  If using Debezium → Kafka instead of MaterializedPostgreSQL:
--
--  CREATE TABLE kafka_mzv_log (raw String)
--  ENGINE = Kafka SETTINGS
--    kafka_broker_list    = 'localhost:9092',
--    kafka_topic_list     = 'siunertaq.public.mzv_triple_log',
--    kafka_group_name     = 'clickhouse-consumer',
--    kafka_format         = 'JSONEachRow',
--    kafka_num_consumers  = 2;
--
--  CREATE MATERIALIZED VIEW kafka_mzv_log_mv TO mzv_triple_stream AS
--  SELECT
--    JSONExtractUInt(raw, 'after', 'log_id')    AS log_id,
--    JSONExtractInt(raw,  'after', 's1')         AS s1,
--    ...
--  FROM kafka_mzv_log;