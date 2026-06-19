{-|
  PetersenMZV.dhall
  modules/postgres-bridge/src/main/resources/PetersenMZV.dhall

  Extends BatchJob.dhall with Petersen graph vertex type.
  Two Dhall interactive modes:
    REPL          → live exploration of vertex → BSD mappings
    dhall-to-json → batch pipeline via DhallBatchRegistry

  Vertex kind ↔ JCL COND ↔ MZV convergence isomorphism:
    Outer(i)     → None         → always execute  (P1/UNSAT, topology ??? dead)
    Inner(0)     → COND=ONLY   → ABEND時のみ実行  (ImaginaryPopperActor boundary)
    Inner(i≥1)   → COND=(0,NE) → エラー時のみ実行 (AffineDual recovery)
-}

let CondOp   = < LT | LE | EQ | NE | GT | GE >
let CondExpr =
      < Compare : { threshold : Natural, op : CondOp }
      | Even    : {}
      | Only    : {}
      >
let BSDVertexTag = < Leech | AffineDual | Padic | Selmer >
let StackInstr   =
      < PushScalar : { n : Natural }
      | PushVec3   : { x : Natural, y : Natural, z : Natural }
      | AddScalar  : {}
      | AddVec3    : {}
      | MulScalar  : {}
      | DotVec3    : {}
      >

-- ─── Petersen vertex type ─────────────────────────────────────────────────
let PetersenVertex =
      < Outer : { phase : Natural }   -- 実部:  even-depth, Frobenius direction
      | Inner : { phase : Natural }   -- 純虚部: odd-depth,  Verschiebung / ImaginaryPopper
      >

-- ─── vertexToBSD ─────────────────────────────────────────────────────────
--   Outer(0)    → Leech       (W0, norm-base)
--   Outer(1..2) → AffineDual  (W12, midpoint)
--   Outer(3..4) → Padic       (W8,  outer rim)
--   Inner(0)    → Selmer      (W16, divergent-pole sector)
--   Inner(1..4) → AffineDual  (recovery target)
let vertexToBSD : PetersenVertex → BSDVertexTag =
  λ(v : PetersenVertex) →
    merge
      { Outer = λ(o : { phase : Natural }) →
          if   Natural/isZero o.phase       then BSDVertexTag.Leech
          else if Natural/lessThan o.phase 3 then BSDVertexTag.AffineDual
          else BSDVertexTag.Padic
      , Inner = λ(i : { phase : Natural }) →
          if Natural/isZero i.phase then BSDVertexTag.Selmer
          else BSDVertexTag.AffineDual
      }
      v

-- ─── vertexToCond ─────────────────────────────────────────────────────────
--   COND semantics encode MZV convergence directly.
let vertexToCond : PetersenVertex → Optional CondExpr =
  λ(v : PetersenVertex) →
    merge
      { Outer = λ(_ : { phase : Natural }) →
          None CondExpr
      , Inner = λ(i : { phase : Natural }) →
          if   Natural/isZero i.phase
          then Some (CondExpr.Only {})
          else Some (CondExpr.Compare { threshold = 0, op = CondOp.NE })
      }
      v

-- ─── vertexToEffectTag ───────────────────────────────────────────────────
let vertexToEffectTag : PetersenVertex → Text =
  λ(v : PetersenVertex) →
    merge
      { Outer = λ(o : { phase : Natural }) → "outer_phase_" ++ Natural/show o.phase
      , Inner = λ(i : { phase : Natural }) → "inner_phase_" ++ Natural/show i.phase
      }
      v

-- ─── MZVStepDef ──────────────────────────────────────────────────────────
let MZVStepDef =
      { name       : Text
      , vertex     : PetersenVertex
      , input_prog : List StackInstr
      , priority   : Natural
      }

-- Auto-derives effect_tag / norm_vertex / cond from vertex.
-- Output is structurally compatible with StepDef in BatchJob.dhall.
let mkMZVStep
    : MZVStepDef
    → { name        : Text
      , effect_tag  : Text
      , cond        : Optional CondExpr
      , norm_vertex : BSDVertexTag
      , input_prog  : List StackInstr
      , priority    : Natural
      }
    = λ(s : MZVStepDef) →
        { name        = s.name
        , effect_tag  = vertexToEffectTag s.vertex
        , cond        = vertexToCond      s.vertex
        , norm_vertex = vertexToBSD       s.vertex
        , input_prog  = s.input_prog
        , priority    = s.priority
        }

-- ─── Example: 4-step job across real and imaginary vertices ──────────────
let S = mkMZVStep
in  { job_name = "PetersenMZVBatch"
    , prime    = 7
    , steps    =
        [ S { name       = "outer-0-leech"
            , vertex     = PetersenVertex.Outer { phase = 0 }
            , input_prog = [ StackInstr.PushScalar { n = 0 } ]
            , priority   = 1
            }
        , S { name       = "outer-2-affine"
            , vertex     = PetersenVertex.Outer { phase = 2 }
            , input_prog = [ StackInstr.PushScalar { n = 12 }
                           , StackInstr.PushScalar { n = 1  }
                           , StackInstr.MulScalar  {}
                           ]
            , priority   = 2
            }
        , S { name       = "inner-0-selmer"
            , vertex     = PetersenVertex.Inner { phase = 0 }
            , input_prog = [ StackInstr.PushScalar { n = 16 } ]
            , priority   = 3
            }
        , S { name       = "inner-1-affine-recover"
            , vertex     = PetersenVertex.Inner { phase = 1 }
            , input_prog = [ StackInstr.PushScalar { n = 0 } ]
            , priority   = 3
            }
        ]
    }
