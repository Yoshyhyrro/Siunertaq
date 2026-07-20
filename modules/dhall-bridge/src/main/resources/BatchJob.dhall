{-|  modules/dhall-bridge/src/main/resources/BatchJob.dhall

     Complete representation of JCL COND statement algebraic semantics via Dhall total functions.
     Embeds a sequence of BSDQuiver stack machine instructions as an "input program" for each step,
     which is then passed to Spring Batch + Pekko JES2 via the Dhall REPL or dhall-to-json.

     Interactive usage in Dhall REPL:
       siunertaq> :load ./BatchJob.dhall
       siunertaq> :let myStep = ...   -- Add or modify steps on the fly

     Usage via dhall-to-json:
       dhall-to-json --file BatchJob.dhall | SiunertaqBatchApp
-}

-- JCL COND=(threshold, op): skip the step if "threshold op previousRC" evaluates to True
let CondOp = < LT | LE | EQ | NE | GT | GE >

-- COND Expression: Compare = conditional skip / Even = execute always / Only = execute only on ABEND
let CondExpr =
      < Compare : { threshold : Natural, op : CondOp }
      | Even    : {}
      | Only    : {}
      | >

-- Dhall mirror of BSDVertex (matches Scala enum .toString())
let BSDVertexTag = < Leech | AffineDual | Padic | Selmer >

-- Dhall mirror of io.siunertaq.expr.Instr
-- Nullary constructors are unitized with {} (e.g., dhall-to-json: {"AddScalar": {}})
let StackInstr =
      < PushScalar : { n : Natural }
      | PushVec3   : { x : Natural, y : Natural, z : Natural }
      | AddScalar  : {}
      | AddVec3    : {}
      | MulScalar  : {}
      | DotVec3    : {}
      >

let StepDef =
      { name        : Text              -- Spring Batch step name
      , effect_tag  : Text              -- BSDArrow.effect_tag (REPL integration key)
      , cond        : Optional CondExpr
      , norm_vertex : BSDVertexTag      -- Target vertex for Yices threshold verification
      , input_prog  : List StackInstr  -- Stack machine input program
      , priority    : Natural           -- Pekko actor execution priority (analogous to JCL PRTY)
      }

let BatchJobDef =
      { job_name : Text
      , prime    : Natural              -- Dieudonné prime (used for BSD threshold verification)
      , steps    : List StepDef
      }

-- Helper: Short-hand helper to construct a Compare COND expression
let mkCond =
      \(t : Natural) -> \(op : CondOp) ->
        Some (CondExpr.Compare { threshold = t, op = op })

in    { job_name = "SiunertaqBatch"
      , prime    = 7
      , steps    =
          [ -- STEP1: Leech -> AffineDual (Frobenius; executes always)
            { name        = "frobenius-compile"
            , effect_tag  = "tensor_bang"
            , cond        = None CondExpr
            , norm_vertex = BSDVertexTag.AffineDual
            , input_prog  =
                [ StackInstr.PushScalar { n = 12 }
                , StackInstr.PushScalar { n = 1 }
                , StackInstr.MulScalar  {=}
                ]
            , priority    = 1
            }
          , -- STEP2: AffineDual -> Padic (Frobenius; skip if previous RC > 4)
            { name        = "padic-lower"
            , effect_tag  = "oplus_padic"
            , cond        = mkCond 4 CondOp.LT   -- COND=(4,LT): skip if 4 < previous RC
            , norm_vertex = BSDVertexTag.Padic
            , input_prog  =
                [ StackInstr.PushVec3 { x = 2, y = 4, z = 0 }
                , StackInstr.PushVec3 { x = 1, y = 0, z = 8 }
                , StackInstr.DotVec3  {=}
                ]
            , priority    = 2
            }
          , -- STEP3: Leech -> Selmer (Verschiebung; skip if previous RC != 0)
            { name        = "selmer-project"
            , effect_tag  = "project_selmer"
            , cond        = mkCond 0 CondOp.NE   -- COND=(0,NE): skip if previous RC != 0
            , norm_vertex = BSDVertexTag.Selmer
            , input_prog  = [ StackInstr.PushScalar { n = 16 } ]
            , priority    = 2
            }
          , -- STEP4: Recovery (Verschiebung; executes only on ABEND = COND=ONLY)
            { name        = "cache-recover"
            , effect_tag  = "recover"
            , cond        = Some (CondExpr.Only {=})
            , norm_vertex = BSDVertexTag.AffineDual
            , input_prog  = [ StackInstr.PushScalar { n = 0 } ]
            , priority    = 3
            }
          ]
      } : BatchJobDef
