{-|
  Shared type schema for the Siunertaq Batch pipeline.
  Factored out of BatchJob.dhall so that both the batch data and the
  SMT-LIB v2.6 generator (ToSMT2.dhall) type-check against the same
  definitions instead of duplicating (and risking drift on) the union types.
-}
-- `cond` follows z/OS JCL's COND= step-control parameter (see build.sbt's
-- batchBridge comment): it gates whether a step runs, evaluated against the
-- *preceding* step's outcome - Compare mirrors COND=(threshold,op), Even/Only
-- mirror COND=EVEN/ONLY (abend-cascade override / error-handler-only). See
-- ToSMT2.dhall's header comment for the exact rules and modeling choices.
let CondOp = < LT | LE | EQ | NE | GT | GE >

let CondExpr =
      < Compare : { threshold : Natural, op : CondOp }
      | Even    : {}
      | Only    : {}
      >

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
  SMT solver metadata schema.
  Target logic defaults to Quantifier-Free Linear Integer Arithmetic (QF_LIA).
-}
let SMTConfig = { export_smt : Bool, target_logic : Text }

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

in  { CondOp = CondOp
    , CondExpr = CondExpr
    , BSDVertexTag = BSDVertexTag
    , StackInstr = StackInstr
    , SMTConfig = SMTConfig
    , StepDef = StepDef
    }
