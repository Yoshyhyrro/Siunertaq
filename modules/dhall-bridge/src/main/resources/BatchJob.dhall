{-|
  Core schema definition for the Siunertaq Batch pipeline.
  Enforces total evaluation and structural rigidity before lowering to JSON.
  Integrates SMT solver metadata to bridge abstract ASTs with verification targets.
-}

let CondOp = < LT | LE | EQ | NE | GT | GE >

let CondExpr =
      < Compare : { threshold : Natural, op : CondOp }
      | Even    : {}
      | Only    : {}
      | >

let BSDVertexTag = < Leech | AffineDual | Padic | Selmer >

let StackInstr =
      < PushScalar : { n : Natural }
      | PushVec3   : { x : Natural, y : Natural, z : Natural }
      | AddScalar  : {}
      | AddVec3    : {}
      | MulScalar  : {}
      | DotVec3    : {}
      >

{-|
  Yices verification metadata schema.
  Target logic defaults to Quantifier-Free Linear Integer Arithmetic (QF_LIA).
-}
let SMTConfig =
      { export_smt   : Bool
      , target_logic : Text
      }

{-|
  Default environment bindings for step definitions.
  Utilizes Dhall's record completion operator (::) to eliminate boilerplate
  and enforce safe defaults for priority and SMT generation.
-}
let StepDef =
      { Type =
          { name        : Text
          , effect_tag  : Text
          , cond        : Optional CondExpr
          , norm_vertex : BSDVertexTag
          , input_prog  : List StackInstr
          , priority    : Natural
          , smt_config  : SMTConfig
          }
      , default =
          { cond       = None CondExpr
          , priority   = 1
          , smt_config = { export_smt = True, target_logic = "QF_LIA" }
          }
      }

let mkCond =
      \(t : Natural) -> \(op : CondOp) ->
        Some (CondExpr.Compare { threshold = t, op = op })

in  { job_name = "SiunertaqBatch_Rigid"
    , prime    = 7
    , steps    =
        [ StepDef::{
          , name        = "frobenius-compile"
          , effect_tag  = "tensor_bang"
          , norm_vertex = BSDVertexTag.AffineDual
          , input_prog  =
              [ StackInstr.PushScalar { n = 12 }
              , StackInstr.PushScalar { n = 1 }
              , StackInstr.MulScalar  {}
              ]
          }
        , StepDef::{
          , name        = "padic-lower"
          , effect_tag  = "oplus_padic"
          , cond        = mkCond 4 CondOp.LT
          , norm_vertex = BSDVertexTag.Padic
          , input_prog  =
              [ StackInstr.PushVec3 { x = 2, y = 4, z = 0 }
              , StackInstr.PushVec3 { x = 1, y = 0, z = 8 }
              , StackInstr.DotVec3  {}
              ]
          , priority    = 2
          }
        ]
    }