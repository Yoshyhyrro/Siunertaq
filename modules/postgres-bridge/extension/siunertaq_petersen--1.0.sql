-- siunertaq_petersen--1.0.sql
-- Petersen graph / MZV vertex tables for postgres-bridge.
-- Requires: siunertaq_forth  (bsd_vertex ENUM, register_and_compile_step())
--
-- Vertex encoding (mirrors PetersenFluidMachine.scala):
--   Outer(i) = i       (i ∈ 0..4, 実部,  even-depth)
--   Inner(i) = i + 5   (i ∈ 0..4, 純虚部, odd-depth)

-- ─── 1. Types ─────────────────────────────────────────────────────────────

CREATE TYPE petersen_sector AS ENUM ('Outer', 'Inner');
CREATE TYPE mzv_part        AS ENUM ('Real', 'Imaginary');

-- ─── 2. Vertex registry ───────────────────────────────────────────────────

CREATE TABLE petersen_vertices (
    vertex_id  SERIAL PRIMARY KEY,
    sector     petersen_sector NOT NULL,
    phase      SMALLINT        NOT NULL CHECK (phase BETWEEN 0 AND 4),
    mzv_part   mzv_part        NOT NULL
               GENERATED ALWAYS AS (
                   CASE sector
                       WHEN 'Outer' THEN 'Real'::mzv_part
                       WHEN 'Inner' THEN 'Imaginary'::mzv_part
                   END
               ) STORED,
    bsd_vertex bsd_vertex      NOT NULL,
    UNIQUE (sector, phase)
);

-- Pre-populate all 10 Petersen vertices.
-- Outer: 実部 / Frobenius direction  (topology ??? = dead code, P1/UNSAT)
-- Inner: 純虚部 / Verschiebung direction (ImaginaryPopperActor boundary)
INSERT INTO petersen_vertices (sector, phase, bsd_vertex) VALUES
    ('Outer', 0, 'Leech'     ::bsd_vertex),  -- Outer(0): norm-base
    ('Outer', 1, 'AffineDual'::bsd_vertex),  -- Outer(1..2): midpoint W12
    ('Outer', 2, 'AffineDual'::bsd_vertex),
    ('Outer', 3, 'Padic'     ::bsd_vertex),  -- Outer(3..4): outer rim W8
    ('Outer', 4, 'Padic'     ::bsd_vertex),
    ('Inner', 0, 'Selmer'    ::bsd_vertex),  -- Inner(0): divergent pole; COND=ONLY
    ('Inner', 1, 'AffineDual'::bsd_vertex),  -- Inner(1..4): recovery path
    ('Inner', 2, 'AffineDual'::bsd_vertex),
    ('Inner', 3, 'Selmer'    ::bsd_vertex),
    ('Inner', 4, 'Selmer'    ::bsd_vertex);

-- ─── 3. MZV traversal audit log ──────────────────────────────────────────

CREATE TABLE mzv_triple_log (
    log_id          SERIAL PRIMARY KEY,
    s1              INT             NOT NULL,
    s2              INT             NOT NULL,
    s3              INT             NOT NULL,
    is_convergent   BOOLEAN         NOT NULL
                    GENERATED ALWAYS AS (s1 > 1) STORED,
    src_sector      petersen_sector NOT NULL,
    src_phase       SMALLINT        NOT NULL,
    tgt_sector      petersen_sector NOT NULL,
    tgt_phase       SMALLINT        NOT NULL,
    was_regularized BOOLEAN         NOT NULL DEFAULT FALSE,
    original_s1     INT,   -- set when was_regularized = TRUE (original s1 = 1)
    compiled_step   TEXT   REFERENCES compiled_words(step_name) ON DELETE SET NULL,
    logged_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ─── 4. Helpers ───────────────────────────────────────────────────────────

-- Petersen (sector, phase) → bsd_vertex name (TEXT castable to bsd_vertex)
CREATE OR REPLACE FUNCTION petersen_to_bsd(
    p_sector TEXT,
    p_phase  INT
) RETURNS TEXT AS $$
    SELECT bsd_vertex::TEXT
    FROM   petersen_vertices
    WHERE  sector = p_sector::petersen_sector
      AND  phase  = p_phase;
$$ LANGUAGE SQL STABLE;

-- ─── 5. ImaginaryPopper registration ─────────────────────────────────────
-- Called by ForthRegistrar.scala after ImaginaryPopperActor completes.
-- Records divergent triple, compiles regularized result as Forth word.

CREATE OR REPLACE FUNCTION register_imaginary_pop(
    p_orig_s1   INT,  p_orig_s2   INT,  p_orig_s3   INT,
    p_reg_s1    INT,  p_reg_s2    INT,  p_reg_s3    INT,
    p_src       TEXT, p_src_phase INT,
    p_tgt       TEXT, p_tgt_phase INT
) RETURNS TEXT AS $$
DECLARE
    v_step TEXT;
    v_bsd  TEXT;
BEGIN
    v_bsd := petersen_to_bsd(p_tgt, p_tgt_phase);
    IF v_bsd IS NULL THEN
        RAISE EXCEPTION 'Petersen vertex %/% not in petersen_vertices', p_tgt, p_tgt_phase;
    END IF;

    v_step := format('imaginary_pop_%s%s_%s%s_%s_%s_%s',
                     p_src, p_src_phase, p_tgt, p_tgt_phase,
                     p_orig_s1, p_orig_s2, p_orig_s3);

    PERFORM register_and_compile_step(
        'PetersenMZVBatch',
        v_step,
        'imaginary_pop',
        v_bsd,
        99,   -- low priority (audit record)
        jsonb_build_array(
            jsonb_build_object('PushScalar', jsonb_build_object('n', p_reg_s1)),
            jsonb_build_object('PushScalar', jsonb_build_object('n', p_reg_s2)),
            jsonb_build_object('PushScalar', jsonb_build_object('n', p_reg_s3))
        )
    );

    INSERT INTO mzv_triple_log (
        s1, s2, s3,
        src_sector, src_phase, tgt_sector, tgt_phase,
        was_regularized, original_s1, compiled_step
    ) VALUES (
        p_reg_s1, p_reg_s2, p_reg_s3,
        p_src::petersen_sector, p_src_phase,
        p_tgt::petersen_sector, p_tgt_phase,
        TRUE, p_orig_s1, v_step
    );

    RETURN v_step;
END;
$$ LANGUAGE plpgsql;
