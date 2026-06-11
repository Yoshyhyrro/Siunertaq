{-|  modules/batch-bridge/src/main/resources/BatchJob.dhall

     JCL COND文の代数的セマンティクスをDhallの全域関数で完全表現。
     BSDQuiverスタックマシン命令列を各ステップの「入力プログラム」として
     埋め込み、Dhall REPL または dhall-to-json 経由で
     Spring Batch + Pekko JES2 に渡す。

     Dhall REPL での対話的利用:
       siunertaq> :load ./BatchJob.dhall
       siunertaq> :let myStep = ...   -- ステップをその場で追加・変更

     dhall-to-json での利用:
       dhall-to-json --file BatchJob.dhall | SiunertaqBatchApp
-}

-- JCL COND=(threshold, op): threshold op previousRC が真ならスキップ
let CondOp = < LT | LE | EQ | NE | GT | GE >

-- COND式: Compare=比較スキップ / Even=必ず実行 / Only=ABEND時のみ実行
let CondExpr =
      < Compare : { threshold : Natural, op : CondOp }
      | Even    : {}
      | Only    : {}
      >

-- BSDVertex のDhallミラー (Scala enum .toString() と一致)
let BSDVertexTag = < Leech | AffineDual | Padic | Selmer >

-- io.siunertaq.expr.Instr のDhallミラー
-- 無引数構成子は {} でunit化 (dhall-to-json: {"AddScalar": {}})
let StackInstr =
      < PushScalar : { n : Natural }
      | PushVec3   : { x : Natural, y : Natural, z : Natural }
      | AddScalar  : {}
      | AddVec3    : {}
      | MulScalar  : {}
      | DotVec3    : {}
      >

let StepDef =
      { name        : Text             -- Spring Batch Step名
      , effect_tag  : Text             -- BSDArrow.effect_tag (REPL連携キー)
      , cond        : Optional CondExpr
      , norm_vertex : BSDVertexTag     -- Yices閾値検証対象頂点
      , input_prog  : List StackInstr  -- スタックマシン入力プログラム
      , priority    : Natural          -- Pekkoアクター起動優先度 (JCL PRTY相当)
      }

let BatchJobDef =
      { job_name : Text
      , prime    : Natural             -- Dieudonne素数 (BSD閾値検証用)
      , steps    : List StepDef
      }

-- ヘルパー: Compare COND を短く書く
let mkCond =
      \(t : Natural) -> \(op : CondOp) ->
        Some (CondExpr.Compare { threshold = t, op = op })

in    { job_name = "SiunertaqBatch"
      , prime    = 7
      , steps    =
          [ -- STEP1: Leech → AffineDual (Frobenius; 常に実行)
            { name        = "frobenius-compile"
            , effect_tag  = "tensor_bang"
            , cond        = None CondExpr
            , norm_vertex = BSDVertexTag.AffineDual
            , input_prog  =
                [ StackInstr.PushScalar { n = 12 }
                , StackInstr.PushScalar { n = 1 }
                , StackInstr.MulScalar  {}
                ]
            , priority    = 1
            }
          , -- STEP2: AffineDual → Padic (Frobenius; 前RC>4でスキップ)
            { name        = "padic-lower"
            , effect_tag  = "oplus_padic"
            , cond        = mkCond 4 CondOp.LT   -- COND=(4,LT): 4<前RC → skip
            , norm_vertex = BSDVertexTag.Padic
            , input_prog  =
                [ StackInstr.PushVec3 { x = 2, y = 4, z = 0 }
                , StackInstr.PushVec3 { x = 1, y = 0, z = 8 }
                , StackInstr.DotVec3  {}
                ]
            , priority    = 2
            }
          , -- STEP3: Leech → Selmer (Verschiebung; 前RC≠0でスキップ)
            { name        = "selmer-project"
            , effect_tag  = "project_selmer"
            , cond        = mkCond 0 CondOp.NE   -- COND=(0,NE): 前RC≠0 → skip
            , norm_vertex = BSDVertexTag.Selmer
            , input_prog  = [ StackInstr.PushScalar { n = 16 } ]
            , priority    = 2
            }
          , -- STEP4: リカバリ (Verschiebung; ABEND時のみ実行 = COND=ONLY)
            { name        = "cache-recover"
            , effect_tag  = "recover"
            , cond        = Some (CondExpr.Only {})
            , norm_vertex = BSDVertexTag.AffineDual
            , input_prog  = [ StackInstr.PushScalar { n = 0 } ]
            , priority    = 3
            }
          ]
      } : BatchJobDef