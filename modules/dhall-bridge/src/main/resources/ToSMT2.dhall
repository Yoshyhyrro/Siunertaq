{-|
  Compiles each `BatchJob.dhall` step into a genuine SMT-LIB v2.6 script.

  IMPORTANT - semantics of `cond` (revised): `cond` follows z/OS JCL's
  `COND=` step-control parameter, per build.sbt's batchBridge comment
  ("JCL COND 文の代数的セマンティクス"). It decides whether a step *runs at
  all*, evaluated against the *preceding* step's outcome - it is NOT a
  property proved about the step's own result (that was last turn's
  interpretation; this turn's context - JCL COND semantics - supersedes it).

  Modeling choices (the schema has no explicit "return code" or "which step"
  target field, so these fill the gap; flagged here rather than left
  implicit):
    - A step's "return code" (RC) is its own computed scalar result.
    - Compare/None/Only only ever look at the IMMEDIATELY PRECEDING step
      (real JCL's default with no stepname checks *all* prior steps; this
      only has one predecessor to check per step, so the distinction is
      moot for now).
    - "Abend" (JCL's failure signal that EVEN/ONLY key off) = this step's
      input_prog hit a stack underflow or type error (interpreter ok=False).
    - COND=(threshold,op) [Compare]: bypass this step if the preceding
      step's RC satisfies `RC op threshold`  (matches real JCL: e.g.
      COND=(4,LT) bypasses if a prior RC < 4). If the preceding step
      abended, this step is bypassed regardless of the comparison (JCL:
      numeric COND does not rescue a step from an abend cascade).
    - Default (no cond / None): bypass only if the preceding step abended.
    - Even: always runs, regardless of the preceding step's outcome.
    - Only: runs only if the preceding step abended (i.e. an error handler).
    - The very first step has no predecessor, so it always runs.

  For every step that DOES run: both a symbolic SMT-LIB trace (SSA-named
  intermediates, so the trace is linear-sized, not duplicated per reference -
  see note at the bottom of this file) and an independent concrete Dhall
  evaluation are computed from the exact same input_prog. The SMT-LIB script
  then asserts the NEGATION of "the two agree" and expects `unsat`: an
  independent solver certifies Dhall's own arithmetic, rather than the
  script simply asserting whatever Dhall already computed.

  Render with: dhall text --file ToSMT2.dhall
-}
let Schema = ./Schema.dhall

let Batch = ./BatchJob.dhall

let CondOp = Schema.CondOp

let CondExpr = Schema.CondExpr

let StackInstr = Schema.StackInstr

let Vec3T = { x : Text, y : Text, z : Text }

-- ══════════════════════════════════════════════════════════════════════════
-- §1  Natural comparisons (Dhall has no built-in <,<=,>,>=,==,!= for Natural)
-- ══════════════════════════════════════════════════════════════════════════
let ltNat : Natural -> Natural -> Bool
    = \(a : Natural) -> \(b : Natural) ->
        if Natural/isZero (Natural/subtract a b) then False else True

let leNat : Natural -> Natural -> Bool
    = \(a : Natural) -> \(b : Natural) -> Natural/isZero (Natural/subtract b a)

let eqNat : Natural -> Natural -> Bool
    = \(a : Natural) -> \(b : Natural) -> leNat a b && leNat b a

let neNat : Natural -> Natural -> Bool
    = \(a : Natural) -> \(b : Natural) -> if eqNat a b then False else True

let gtNat : Natural -> Natural -> Bool = \(a : Natural) -> \(b : Natural) -> ltNat b a

let geNat : Natural -> Natural -> Bool = \(a : Natural) -> \(b : Natural) -> leNat b a

let compareDesc
    : CondOp -> Text
    = \(op : CondOp) -> merge { LT = "<", LE = "<=", EQ = "=", NE = "!=", GT = ">", GE = ">=" } op

let cmpNat
    : Natural -> CondOp -> Natural -> Bool
    = \(rc : Natural) -> \(op : CondOp) -> \(threshold : Natural) ->
        merge
          { LT = ltNat rc threshold
          , LE = leNat rc threshold
          , EQ = eqNat rc threshold
          , NE = neNat rc threshold
          , GT = gtNat rc threshold
          , GE = geNat rc threshold
          }
          op

-- ══════════════════════════════════════════════════════════════════════════
-- §2  Symbolic evaluator: interprets input_prog into an SMT-LIB v2.6 trace
--     with SSA-named intermediates (each result gets ONE fresh name,
--     referenced by later instructions - not re-substituted/inlined, so
--     trace size stays linear in program length, not exponential).
-- ══════════════════════════════════════════════════════════════════════════
let StackSlot = < SVal : Text | VVal : Vec3T >

let OptSlot = Optional StackSlot

let Split = { first : OptSlot, second : OptSlot, rest : List StackSlot }

let splitAt2
    : List StackSlot -> Split
    = \(xs : List StackSlot) ->
        List/fold
          StackSlot
          (List/reverse StackSlot xs)
          Split
          ( \(x : StackSlot) -> \(acc : Split) ->
              merge
                { None = acc // { first = Some x }
                , Some = \(_ : StackSlot) ->
                    merge
                      { None = acc // { second = Some x }
                      , Some = \(_ : StackSlot) -> acc // { rest = acc.rest # [ x ] }
                      }
                      acc.second
                }
                acc.first
          )
          { first = None StackSlot, second = None StackSlot, rest = [] : List StackSlot }

let Acc =
      { ok : Bool, msg : Text, stack : List StackSlot, smt : Text, n : Natural, prefix : Text }

let fresh : Text -> Natural -> Text = \(prefix : Text) -> \(n : Natural) -> prefix ++ Natural/show n

let fail : Text -> Acc -> Acc = \(m : Text) -> \(acc : Acc) -> acc // { ok = False, msg = m }

let OptSS = Optional { a : Text, b : Text, rest : List StackSlot }

let popTwoScalars
    : List StackSlot -> OptSS
    = \(stack : List StackSlot) ->
        let sp = splitAt2 stack
        in  merge
              { None = None { a : Text, b : Text, rest : List StackSlot }
              , Some =
                  \(top : StackSlot) ->
                    merge
                      { None = None { a : Text, b : Text, rest : List StackSlot }
                      , Some =
                          \(snd : StackSlot) ->
                            merge
                              { SVal =
                                  \(av : Text) ->
                                    merge
                                      { SVal = \(bv : Text) -> Some { a = av, b = bv, rest = sp.rest }
                                      , VVal = \(_ : Vec3T) -> None { a : Text, b : Text, rest : List StackSlot }
                                      }
                                      snd
                              , VVal = \(_ : Vec3T) -> None { a : Text, b : Text, rest : List StackSlot }
                              }
                              top
                      }
                      sp.second
              }
              sp.first

let OptVV = Optional { a : Vec3T, b : Vec3T, rest : List StackSlot }

let popTwoVectors
    : List StackSlot -> OptVV
    = \(stack : List StackSlot) ->
        let sp = splitAt2 stack
        in  merge
              { None = None { a : Vec3T, b : Vec3T, rest : List StackSlot }
              , Some =
                  \(top : StackSlot) ->
                    merge
                      { None = None { a : Vec3T, b : Vec3T, rest : List StackSlot }
                      , Some =
                          \(snd : StackSlot) ->
                            merge
                              { VVal =
                                  \(av : Vec3T) ->
                                    merge
                                      { VVal = \(bv : Vec3T) -> Some { a = av, b = bv, rest = sp.rest }
                                      , SVal = \(_ : Text) -> None { a : Vec3T, b : Vec3T, rest : List StackSlot }
                                      }
                                      snd
                              , SVal = \(_ : Text) -> None { a : Vec3T, b : Vec3T, rest : List StackSlot }
                              }
                              top
                      }
                      sp.second
              }
              sp.first

let step
    : StackInstr -> Acc -> Acc
    = \(instr : StackInstr) -> \(acc : Acc) ->
        if    acc.ok == False
        then  acc
        else
          merge
            { PushScalar =
                \(p : { n : Natural }) ->
                  let v = fresh acc.prefix acc.n
                  in  acc
                      //  { stack = [ StackSlot.SVal v ] # acc.stack
                          , smt =
                                acc.smt
                              ++ "(declare-const ${v} Int)\n(assert (= ${v} ${Natural/show p.n}))\n"
                          , n = acc.n + 1
                          }
            , PushVec3 =
                \(p : { x : Natural, y : Natural, z : Natural }) ->
                  let vx = fresh acc.prefix acc.n
                  let vy = fresh acc.prefix (acc.n + 1)
                  let vz = fresh acc.prefix (acc.n + 2)
                  in  acc
                      //  { stack = [ StackSlot.VVal { x = vx, y = vy, z = vz } ] # acc.stack
                          , smt =
                                  acc.smt
                              ++ "(declare-const ${vx} Int)\n(assert (= ${vx} ${Natural/show p.x}))\n"
                              ++ "(declare-const ${vy} Int)\n(assert (= ${vy} ${Natural/show p.y}))\n"
                              ++ "(declare-const ${vz} Int)\n(assert (= ${vz} ${Natural/show p.z}))\n"
                          , n = acc.n + 3
                          }
            , AddScalar =
                \(_ : {}) ->
                  merge
                    { None = fail "AddScalar: stack underflow or type error" acc
                    , Some =
                        \(r : { a : Text, b : Text, rest : List StackSlot }) ->
                          let v = fresh acc.prefix acc.n
                          in  acc
                              //  { stack = [ StackSlot.SVal v ] # r.rest
                                  , smt = acc.smt ++ "(declare-const ${v} Int)\n(assert (= ${v} (+ ${r.a} ${r.b})))\n"
                                  , n = acc.n + 1
                                  }
                    }
                    (popTwoScalars acc.stack)
            , MulScalar =
                \(_ : {}) ->
                  merge
                    { None = fail "MulScalar: stack underflow or type error" acc
                    , Some =
                        \(r : { a : Text, b : Text, rest : List StackSlot }) ->
                          let v = fresh acc.prefix acc.n
                          in  acc
                              //  { stack = [ StackSlot.SVal v ] # r.rest
                                  , smt = acc.smt ++ "(declare-const ${v} Int)\n(assert (= ${v} (* ${r.a} ${r.b})))\n"
                                  , n = acc.n + 1
                                  }
                    }
                    (popTwoScalars acc.stack)
            , AddVec3 =
                \(_ : {}) ->
                  merge
                    { None = fail "AddVec3: stack underflow or type error" acc
                    , Some =
                        \(r : { a : Vec3T, b : Vec3T, rest : List StackSlot }) ->
                          let vx = fresh acc.prefix acc.n
                          let vy = fresh acc.prefix (acc.n + 1)
                          let vz = fresh acc.prefix (acc.n + 2)
                          in  acc
                              //  { stack = [ StackSlot.VVal { x = vx, y = vy, z = vz } ] # r.rest
                                  , smt =
                                          acc.smt
                                      ++  "(declare-const ${vx} Int)\n(assert (= ${vx} (+ ${r.a.x} ${r.b.x})))\n"
                                      ++  "(declare-const ${vy} Int)\n(assert (= ${vy} (+ ${r.a.y} ${r.b.y})))\n"
                                      ++  "(declare-const ${vz} Int)\n(assert (= ${vz} (+ ${r.a.z} ${r.b.z})))\n"
                                  , n = acc.n + 3
                                  }
                    }
                    (popTwoVectors acc.stack)
            , DotVec3 =
                \(_ : {}) ->
                  merge
                    { None = fail "DotVec3: stack underflow or type error" acc
                    , Some =
                        \(r : { a : Vec3T, b : Vec3T, rest : List StackSlot }) ->
                          let v = fresh acc.prefix acc.n
                          in  acc
                              //  { stack = [ StackSlot.SVal v ] # r.rest
                                  , smt =
                                        acc.smt
                                      ++  "(declare-const ${v} Int)\n(assert (= ${v} (+ (* ${r.a.x} ${r.b.x}) (+ (* ${r.a.y} ${r.b.y}) (* ${r.a.z} ${r.b.z})))))\n"
                                  , n = acc.n + 1
                                  }
                    }
                    (popTwoVectors acc.stack)
            }
            instr

let runProgram
    : Text -> List StackInstr -> Acc
    = \(prefix : Text) -> \(prog : List StackInstr) ->
        List/fold
          StackInstr
          (List/reverse StackInstr prog)
          Acc
          step
          { ok = True, msg = "", stack = [] : List StackSlot, smt = "", n = 0, prefix = prefix }

-- symbolic result variable name, if the trace ended with exactly one Scalar
let symResultVar
    : Acc -> Optional Text
    = \(acc : Acc) ->
        if    acc.ok == False
        then  None Text
        else
          let sp = splitAt2 acc.stack
          in  merge
                { None = None Text
                , Some =
                    \(top : StackSlot) ->
                      merge
                        { Some = \(_ : StackSlot) -> None Text
                        , None = merge { SVal = \(v : Text) -> Some v, VVal = \(_ : Vec3T) -> None Text } top
                        }
                        sp.second
                }
                sp.first

-- ══════════════════════════════════════════════════════════════════════════
-- §3  Concrete evaluator: the SAME instruction semantics, real Natural
--     arithmetic. Used to (a) drive JCL gating decisions and (b) give the
--     SMT-LIB script something independent to cross-check against.
-- ══════════════════════════════════════════════════════════════════════════
let CVal = < CS : Natural | CV : { x : Natural, y : Natural, z : Natural } >

let COptSlot = Optional CVal

let CSplit = { first : COptSlot, second : COptSlot, rest : List CVal }

let csplitAt2
    : List CVal -> CSplit
    = \(xs : List CVal) ->
        List/fold
          CVal
          (List/reverse CVal xs)
          CSplit
          ( \(x : CVal) -> \(acc : CSplit) ->
              merge
                { None = acc // { first = Some x }
                , Some = \(_ : CVal) ->
                    merge
                      { None = acc // { second = Some x }
                      , Some = \(_ : CVal) -> acc // { rest = acc.rest # [ x ] }
                      }
                      acc.second
                }
                acc.first
          )
          { first = None CVal, second = None CVal, rest = [] : List CVal }

let CAcc = { ok : Bool, msg : Text, stack : List CVal }

let cfail : Text -> CAcc -> CAcc = \(m : Text) -> \(acc : CAcc) -> acc // { ok = False, msg = m }

let cpop2S
    : List CVal -> Optional { a : Natural, b : Natural, rest : List CVal }
    = \(stack : List CVal) ->
        let sp = csplitAt2 stack
        in  merge
              { None = None { a : Natural, b : Natural, rest : List CVal }
              , Some =
                  \(top : CVal) ->
                    merge
                      { None = None { a : Natural, b : Natural, rest : List CVal }
                      , Some =
                          \(snd : CVal) ->
                            merge
                              { CS =
                                  \(av : Natural) ->
                                    merge
                                      { CS = \(bv : Natural) -> Some { a = av, b = bv, rest = sp.rest }
                                      , CV = \(_ : { x : Natural, y : Natural, z : Natural }) -> None { a : Natural, b : Natural, rest : List CVal }
                                      }
                                      snd
                              , CV = \(_ : { x : Natural, y : Natural, z : Natural }) -> None { a : Natural, b : Natural, rest : List CVal }
                              }
                              top
                      }
                      sp.second
              }
              sp.first

let VN = { x : Natural, y : Natural, z : Natural }

let cpop2V
    : List CVal -> Optional { a : VN, b : VN, rest : List CVal }
    = \(stack : List CVal) ->
        let sp = csplitAt2 stack
        in  merge
              { None = None { a : VN, b : VN, rest : List CVal }
              , Some =
                  \(top : CVal) ->
                    merge
                      { None = None { a : VN, b : VN, rest : List CVal }
                      , Some =
                          \(snd : CVal) ->
                            merge
                              { CV =
                                  \(av : VN) ->
                                    merge
                                      { CV = \(bv : VN) -> Some { a = av, b = bv, rest = sp.rest }
                                      , CS = \(_ : Natural) -> None { a : VN, b : VN, rest : List CVal }
                                      }
                                      snd
                              , CS = \(_ : Natural) -> None { a : VN, b : VN, rest : List CVal }
                              }
                              top
                      }
                      sp.second
              }
              sp.first

let cstep
    : StackInstr -> CAcc -> CAcc
    = \(instr : StackInstr) -> \(acc : CAcc) ->
        if    acc.ok == False
        then  acc
        else
          merge
            { PushScalar = \(p : { n : Natural }) -> acc // { stack = [ CVal.CS p.n ] # acc.stack }
            , PushVec3 =
                \(p : { x : Natural, y : Natural, z : Natural }) ->
                  acc // { stack = [ CVal.CV { x = p.x, y = p.y, z = p.z } ] # acc.stack }
            , AddScalar =
                \(_ : {}) ->
                  merge
                    { None = cfail "AddScalar: stack underflow or type error" acc
                    , Some = \(r : { a : Natural, b : Natural, rest : List CVal }) -> acc // { stack = [ CVal.CS (r.a + r.b) ] # r.rest }
                    }
                    (cpop2S acc.stack)
            , MulScalar =
                \(_ : {}) ->
                  merge
                    { None = cfail "MulScalar: stack underflow or type error" acc
                    , Some = \(r : { a : Natural, b : Natural, rest : List CVal }) -> acc // { stack = [ CVal.CS (r.a * r.b) ] # r.rest }
                    }
                    (cpop2S acc.stack)
            , AddVec3 =
                \(_ : {}) ->
                  merge
                    { None = cfail "AddVec3: stack underflow or type error" acc
                    , Some =
                        \(r : { a : VN, b : VN, rest : List CVal }) ->
                          acc // { stack = [ CVal.CV { x = r.a.x + r.b.x, y = r.a.y + r.b.y, z = r.a.z + r.b.z } ] # r.rest }
                    }
                    (cpop2V acc.stack)
            , DotVec3 =
                \(_ : {}) ->
                  merge
                    { None = cfail "DotVec3: stack underflow or type error" acc
                    , Some =
                        \(r : { a : VN, b : VN, rest : List CVal }) ->
                          acc // { stack = [ CVal.CS (r.a.x * r.b.x + r.a.y * r.b.y + r.a.z * r.b.z) ] # r.rest }
                    }
                    (cpop2V acc.stack)
            }
            instr

let crunProgram
    : List StackInstr -> CAcc
    = \(prog : List StackInstr) ->
        List/fold StackInstr (List/reverse StackInstr prog) CAcc cstep { ok = True, msg = "", stack = [] : List CVal }

let finalRC
    : CAcc -> Optional Natural
    = \(acc : CAcc) ->
        if    acc.ok == False
        then  None Natural
        else
          let sp = csplitAt2 acc.stack
          in  merge
                { None = None Natural
                , Some =
                    \(top : CVal) ->
                      merge
                        { Some = \(_ : CVal) -> None Natural
                        , None = merge { CS = \(v : Natural) -> Some v, CV = \(_ : VN) -> None Natural } top
                        }
                        sp.second
                }
                sp.first

-- ══════════════════════════════════════════════════════════════════════════
-- §4  JCL-style gating
-- ══════════════════════════════════════════════════════════════════════════
let StepOutcome =
      < RanOk : { rc : Natural } | RanOddResult : {} | RanFailed : { msg : Text } | Bypassed : {} >

let isFailedOutcome
    : StepOutcome -> Bool
    = \(o : StepOutcome) ->
        merge
          { RanOk = \(_ : { rc : Natural }) -> False
          , RanOddResult = \(_ : {}) -> False
          , RanFailed = \(_ : { msg : Text }) -> True
          , Bypassed = \(_ : {}) -> False
          }
          o

let outcomeRC
    : StepOutcome -> Optional Natural
    = \(o : StepOutcome) ->
        merge
          { RanOk = \(r : { rc : Natural }) -> Some r.rc
          , RanOddResult = \(_ : {}) -> None Natural
          , RanFailed = \(_ : { msg : Text }) -> None Natural
          , Bypassed = \(_ : {}) -> None Natural
          }
          o

let GateResult = { run : Bool, note : Text }

let decideGate
    : Natural -> StepOutcome -> Optional CondExpr -> GateResult
    = \(idx : Natural) -> \(prev : StepOutcome) -> \(cond : Optional CondExpr) ->
        if    Natural/isZero (Natural/subtract 1 idx)
        then  { run = True, note = "first step in the batch - always runs" }
        else
          let failed = isFailedOutcome prev
          in  merge
                { None =
                    if    failed
                    then  { run = False, note = "default COND: bypassed because the preceding step abended" }
                    else  { run = True, note = "default COND: preceding step did not abend" }
                , Some =
                    \(c : CondExpr) ->
                      merge
                        { Even =
                            \(_ : {}) ->
                              { run = True, note = "COND=EVEN: runs regardless of the preceding step's outcome" }
                        , Only =
                            \(_ : {}) ->
                              if    failed
                              then  { run = True, note = "COND=ONLY: runs because the preceding step abended" }
                              else  { run = False, note = "COND=ONLY: bypassed - the preceding step did not abend" }
                        , Compare =
                            \(p : { threshold : Natural, op : CondOp }) ->
                              if    failed
                              then  { run = False
                                    , note = "COND=(${Natural/show p.threshold},${compareDesc p.op}): bypassed - preceding step abended (numeric COND does not override an abend cascade)"
                                    }
                              else
                                merge
                                  { None =
                                      { run = True
                                      , note = "COND=(${Natural/show p.threshold},${compareDesc p.op}): preceding step had no numeric RC - running by default"
                                      }
                                  , Some =
                                      \(rc : Natural) ->
                                        if    cmpNat rc p.op p.threshold
                                        then  { run = False
                                              , note = "COND=(${Natural/show p.threshold},${compareDesc p.op}): bypassed - preceding RC=${Natural/show rc} ${compareDesc p.op} ${Natural/show p.threshold}"
                                              }
                                        else  { run = True
                                              , note = "COND=(${Natural/show p.threshold},${compareDesc p.op}): runs - preceding RC=${Natural/show rc} NOT ${compareDesc p.op} ${Natural/show p.threshold}"
                                              }
                                  }
                                  (outcomeRC prev)
                        }
                        c
                }
                cond

-- ══════════════════════════════════════════════════════════════════════════
-- §5  Per-step assembly + fold over the whole batch
-- ══════════════════════════════════════════════════════════════════════════
let wrapStep
    : Natural -> StepOutcome -> Schema.StepDef.Type -> { text : Text, outcome : StepOutcome }
    = \(idx : Natural) -> \(prevOutcome : StepOutcome) -> \(s : Schema.StepDef.Type) ->
        let gate = decideGate idx prevOutcome s.cond

        let header = "; ---- step ${Natural/show idx}: ${s.name} (priority ${Natural/show s.priority}) ----\n"

        in  if    gate.run == False
            then  { text = header ++ "; BYPASSED - ${gate.note}\n\n", outcome = StepOutcome.Bypassed {=} }
            else
              let symAcc = runProgram "s${Natural/show idx}_v" s.input_prog

              let conAcc = crunProgram s.input_prog

              in  if    symAcc.ok == False || conAcc.ok == False
                  then  { text =
                              header
                          ++  "; ${gate.note}\n"
                          ++  "; RUNS then ABENDS: ${symAcc.msg}${conAcc.msg}\n\n"
                        , outcome = StepOutcome.RanFailed { msg = symAcc.msg ++ conAcc.msg }
                        }
                  else
                    merge
                      { None =
                          -- ran cleanly but did not reduce to a single Scalar (e.g. ended on
                          -- a Vec3, or left >1 value) - nothing to cross-check numerically
                          { text =
                                header
                            ++  "; ${gate.note}\n"
                            ++  "; result is not a single Scalar - checking trace consistency only\n"
                            ++  "(push 1)\n"
                            ++  symAcc.smt
                            ++  "(check-sat) ; EXPECT: sat\n"
                            ++  "(pop 1)\n\n"
                          , outcome = StepOutcome.RanOddResult {=}
                          }
                      , Some =
                          \(rc : Natural) ->
                            merge
                              { None =
                                  -- shouldn't happen if conAcc.ok, but keep the fold total
                                  { text = header ++ "; ${gate.note}\n; internal error: no symbolic result var\n\n"
                                  , outcome = StepOutcome.RanFailed { msg = "no symbolic result var" }
                                  }
                              , Some =
                                  \(rvar : Text) ->
                                    { text =
                                          header
                                      ++  "; ${gate.note}\n"
                                      ++  "; cross-checking the SMT-LIB trace against Dhall's own computation (RC=${Natural/show rc})\n"
                                      ++  "(push 1)\n"
                                      ++  symAcc.smt
                                      ++  "(assert (not (= ${rvar} ${Natural/show rc})))\n"
                                      ++  "(check-sat) ; EXPECT: unsat\n"
                                      ++  "(pop 1)\n\n"
                                    , outcome = StepOutcome.RanOk { rc = rc }
                                    }
                              }
                              (symResultVar symAcc)
                      }
                      (finalRC conAcc)

let body =
      List/fold
        Schema.StepDef.Type
        (List/reverse Schema.StepDef.Type Batch.steps)
        { text : Text, idx : Natural, prevOutcome : StepOutcome }
        ( \(s : Schema.StepDef.Type) -> \(acc : { text : Text, idx : Natural, prevOutcome : StepOutcome }) ->
            let r = wrapStep acc.idx acc.prevOutcome s
            in  { text = acc.text ++ r.text, idx = acc.idx + 1, prevOutcome = r.outcome }
        )
        { text = "", idx = 1, prevOutcome = StepOutcome.Bypassed {=} }

in      "; Auto-generated from BatchJob.dhall by ToSMT2.dhall - do not hand-edit.\n"
    ++  "; job_name: ${Batch.job_name}\n"
    ++  "; cond = JCL-style step gating (see this file's header comment); each\n"
    ++  "; running step's (check-sat) cross-checks this trace against Dhall's\n"
    ++  "; own concrete evaluation of the same input_prog.\n"
    ++  "(set-logic QF_LIA)\n\n"
    ++  body.text
    ++  "; ---- end of generated script ----\n"
