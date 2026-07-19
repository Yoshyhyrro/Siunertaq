{-|
  Core schema definition for the Siunertaq Batch pipeline.
  Enforces total evaluation and structural rigidity before lowering to JSON.
  Integrates SMT solver metadata to bridge abstract ASTs with verification targets.

  NOTE: this file previously defined CondOp/CondExpr/BSDVertexTag/StackInstr/
  SMTConfig/StepDef inline. They now live in ./Schema.dhall so that the
  SMT-LIB v2.6 generator (ToSMT2.dhall) type-checks against the exact same
  definitions instead of a hand-copied duplicate that could drift out of sync.
-}
let Schema = ./Schema.dhall

let CondOp = Schema.CondOp

let CondExpr = Schema.CondExpr

let StackInstr = Schema.StackInstr

let StepDef = Schema.StepDef

let mkCond =
      \(t : Natural) -> \(op : CondOp) ->
        Some (CondExpr.Compare { threshold = t, op = op })

in  { job_name = "SiunertaqBatch_Rigid"
    , prime    = 7
    , steps    =
        [ StepDef::{
          , name        = "frobenius-compile"
          , effect_tag  = "tensor_bang"
          , norm_vertex = Schema.BSDVertexTag.AffineDual
          , input_prog  =
              [ StackInstr.PushScalar { n = 12 }
              , StackInstr.PushScalar { n = 1 }
              -- was `StackInstr.MulScalar {}` - {} is the *type* of the empty
              -- record; {=} is its unique *value*. A bare union alternative
              -- with an empty-record payload must be applied to a value, so
              -- this needs {=}, not {}. (Confirmed against dhall 1.42.2:
              -- `{}` here fails to type-check with "Wrong type of function
              -- argument".)
              , StackInstr.MulScalar  {=}
              ]
          }
        , StepDef::{
          , name        = "padic-lower"
          , effect_tag  = "oplus_padic"
          , cond        = mkCond 4 CondOp.LT
          , norm_vertex = Schema.BSDVertexTag.Padic
          , input_prog  =
              [ StackInstr.PushVec3 { x = 2, y = 4, z = 0 }
              , StackInstr.PushVec3 { x = 1, y = 0, z = 8 }
              , StackInstr.DotVec3  {=}
              ]
          , priority    = 2
          }
        ]
    }
