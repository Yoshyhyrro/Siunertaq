{-|  modules/dhall-bridge/src/main/resources/BatchJob.dhall

     Complete representation of JCL COND statement algebraic semantics via Dhall total functions.
     Embeds a sequence of BSDQuiver stack machine instructions as an "input program" for each step,
     which is then passed to Spring Batch + Pekko JES2 via the Dhall REPL or dhall-to-json.

     Interactive usage in Dhall REPL:
       siunertaq> :load ./BatchJob.dhall
       siunertaq> :let myStep = ...   -- Add or modify steps on the fly

     Usage via dhall-to-json:
       dhall-to-json --file BatchJob.dhall | SiunertaqBatchApp

     ── JSON shape fixes (2026-07) ─────────────────────────────────────────
     dhall-to-json strips union constructor names by design (see
     https://github.com/dhall-lang/dhall-haskell/issues/1383) - fine for
     unions whose alternatives have distinct JSON shapes, but StackInstr and
     CondExpr both have multiple *empty-payload* alternatives that used to
     collapse to indistinguishable bare `{}` (confirmed empirically).

     input_prog specifically has an EXISTING canonical JSON convention
     already shared by three other systems - see core's Program.scala
     (Program.toJson), used by ClassASTBridge, ForthRegistrar/Postgres JSONB,
     and Perl's StackMachine.pm: {"PushScalar":{"n":12}}, {"AddScalar":{}},
     etc. So input_prog now bypasses dhall-to-json's automatic union
     conversion entirely: stackInstrToJSON below builds that exact shape by
     hand via Prelude.JSON, so the Dhall path produces the SAME
     intermediate representation as those other three - not a fourth,
     incompatible one.

     CondExpr/CondOp have no such pre-existing convention elsewhere (they're
     Dhall/batch-specific), so those alternatives carry an explicit `tag`
     field instead (a singleton-enum discriminator - dhall-to-json renders
     single-alternative label-only unions as plain strings), which is
     simpler and doesn't fight the tool. norm_vertex was already an
     all-bare-label enum (renders as a plain string already); no change
     needed there, only BatchJobDef.scala's decoder needed fixing.
-}

let JSON = https://raw.githubusercontent.com/dhall-lang/dhall-lang/v23.0.0/Prelude/JSON/package.dhall
              sha256:5f98b7722fd13509ef448b075e02b9ff98312ae7a406cf53ed25012dbc9990ac

-- JCL COND=(threshold, op): skip the step if "threshold op previousRC" evaluates to True
let CondOp = < LT | LE | EQ | NE | GT | GE >

let CondExprTag = < Compare | Even | Only >

-- COND Expression: Compare = conditional skip / Even = execute always / Only = execute only on ABEND
let CondExpr =
      < Compare : { tag : CondExprTag, threshold : Natural, op : CondOp }
      | Even    : { tag : CondExprTag }
      | Only    : { tag : CondExprTag }
      >

-- Dhall mirror of BSDVertex (matches Scala enum .toString())
let BSDVertexTag = < Leech | AffineDual | Padic | Selmer >

-- Dhall mirror of io.siunertaq.expr.Instr (kept plain/untagged: converted to
-- JSON explicitly via stackInstrToJSON below, matching core's Program.toJson
-- byte-for-byte rather than going through dhall-to-json's union stripping.)
let StackInstr =
      < PushScalar : { n : Natural }
      | PushVec3   : { x : Natural, y : Natural, z : Natural }
      | AddScalar  : {}
      | AddVec3    : {}
      | MulScalar  : {}
      | DotVec3    : {}
      >

-- Mirrors Program.toJson in core/src/main/scala/io/siunertaq/expr/Program.scala
-- EXACTLY: {"PushScalar":{"n":12}}, {"AddScalar":{}}, etc.
let stackInstrToJSON
    : StackInstr -> JSON.Type
    = \(instr : StackInstr) ->
        merge
          { PushScalar =
              \(p : { n : Natural }) ->
                JSON.object [ { mapKey = "PushScalar", mapValue = JSON.object [ { mapKey = "n", mapValue = JSON.natural p.n } ] } ]
          , PushVec3 =
              \(p : { x : Natural, y : Natural, z : Natural }) ->
                JSON.object
                  [ { mapKey = "PushVec3"
                    , mapValue =
                        JSON.object
                          [ { mapKey = "x", mapValue = JSON.natural p.x }
                          , { mapKey = "y", mapValue = JSON.natural p.y }
                          , { mapKey = "z", mapValue = JSON.natural p.z }
                          ]
                    }
                  ]
          , AddScalar = \(_ : {}) -> JSON.object [ { mapKey = "AddScalar", mapValue = JSON.object ([] : List { mapKey : Text, mapValue : JSON.Type }) } ]
          , AddVec3   = \(_ : {}) -> JSON.object [ { mapKey = "AddVec3",   mapValue = JSON.object ([] : List { mapKey : Text, mapValue : JSON.Type }) } ]
          , MulScalar = \(_ : {}) -> JSON.object [ { mapKey = "MulScalar", mapValue = JSON.object ([] : List { mapKey : Text, mapValue : JSON.Type }) } ]
          , DotVec3   = \(_ : {}) -> JSON.object [ { mapKey = "DotVec3",   mapValue = JSON.object ([] : List { mapKey : Text, mapValue : JSON.Type }) } ]
          }
          instr

-- List/map isn't a core Dhall builtin; reuse the List/fold + List/reverse
-- pattern (order-preserving, validated earlier in this project) rather than
-- pull in a second Prelude dependency just for this.
let stackInstrListToJSON
    : List StackInstr -> List JSON.Type
    = \(xs : List StackInstr) ->
        List/fold
          StackInstr
          (List/reverse StackInstr xs)
          (List JSON.Type)
          (\(x : StackInstr) -> \(acc : List JSON.Type) -> acc # [ stackInstrToJSON x ])
          ([] : List JSON.Type)

let StepDef =
      { name        : Text              -- Spring Batch step name
      , effect_tag  : Text              -- BSDArrow.effect_tag (REPL integration key)
      , cond        : Optional CondExpr
      , norm_vertex : BSDVertexTag      -- Target vertex for Yices threshold verification
      , input_prog  : List JSON.Type    -- Stack machine input program (canonical IR shape - see header)
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
        Some (CondExpr.Compare { tag = CondExprTag.Compare, threshold = t, op = op })

in    { job_name = "SiunertaqBatch"
      , prime    = 7
      , steps    =
          [ -- STEP1: Leech -> AffineDual (Frobenius; executes always)
            { name        = "frobenius-compile"
            , effect_tag  = "tensor_bang"
            , cond        = None CondExpr
            , norm_vertex = BSDVertexTag.AffineDual
            , input_prog  =
                stackInstrListToJSON
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
                stackInstrListToJSON
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
            , input_prog  = stackInstrListToJSON [ StackInstr.PushScalar { n = 16 } ]
            , priority    = 2
            }
          , -- STEP4: Recovery (Verschiebung; executes only on ABEND = COND=ONLY)
            { name        = "cache-recover"
            , effect_tag  = "recover"
            , cond        = Some (CondExpr.Only { tag = CondExprTag.Only })
            , norm_vertex = BSDVertexTag.AffineDual
            , input_prog  = stackInstrListToJSON [ StackInstr.PushScalar { n = 0 } ]
            , priority    = 3
            }
          ]
      } : BatchJobDef
