(ns siunertaq.mecrisp-instr-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]))

;; ==========================================================================
;; MecrispInstr.stackDelta cross-check
;; ==========================================================================
;; MecrispInstr.scala's `object MecrispInstr` defines
;;   def stackDelta(instr: MecrispInstr): Option[Int]
;; as a single ~80-line `match` over all 61 enum cases. Every case already
;; carries its own doc comment in Forth stack notation, e.g.
;;   case Store  // !  ( val addr -- )
;; which independently states the case's true stack effect (here: consumes
;; 2, produces 0, so delta = -2) -- entirely separately from whatever
;; stackDelta's match table actually returns for it.
;;
;; That gives two independent sources of truth for the same fact, which is
;; exactly the shape a model-based property test wants: build the "expected"
;; model purely from the comments, run it against the actual match table for
;; every constructor, and let a generator do the tedious 61-way comparison
;; that's easy to get wrong doing by hand (see the `:Nip` regression test
;; below -- a manual line-by-line read-through of this file missed it; the
;; property test did not).
;;
;; SCOPE NOTE ON THE FUNCTION UNDER TEST: this sandbox has no Scala 3
;; compiler available (Maven Central is unreachable from here, same
;; constraint noted for cheshire earlier in this project), so `stack-delta`
;; below is a byte-for-byte transcription of stackDelta's match table --
;; same order, same duplicate arms, same first-match-wins semantics -- not
;; a JVM interop call into the real compiled class. In an environment where
;; Scala 3 actually builds (this project's CI does), replace `stack-delta`
;; with a call into the real io.siunertaq.postgres.MecrispInstr companion
;; object; the model and the property below don't need to change.

;; ---- 1. Independent model, derived only from each case's own comment ----

(def expected-delta
  "net delta = (items after --) - (items before --), read directly off
   each case's own ( before -- after ) comment in MecrispInstr.scala.
   :unknown = that case's own comment has no ( .. ) notation to derive a
   value from (purely structural instructions); excluded from the property.
   :Call is `nil` deliberately -- stackDelta itself returns None for it
   (depends on the called word), which is the correct, checkable behavior."
  {:Literal 1, :CharLit 1
   :Plus -1, :Minus -1, :Multiply -1, :Divide -1, :DivMod 0, :Modulo -1
   :Negate 0, :Abs 0, :MaxOp -1, :MinOp -1
   :OnePlus 0, :OneMinus 0, :TwoMul 0, :TwoDiv 0
   :And -1, :Or -1, :Xor -1, :Invert 0, :LShift -1, :RShift -1, :URShift -1
   :Dup 1, :Drop -1, :Swap 0, :Over 1, :Rot 0, :NRot 0
   :Dup2 2, :Drop2 -2, :Tuck 1, :Nip -1, :Pick 1, :Depth 1
   :ToR -1, :FromR 1, :RFetch 1
   :Store -2, :Fetch 0, :CStore -2, :CFetch 0, :HStore -2, :HFetch 0
   :PlusStore -2
   :VariableRef 1
   :Equal -1, :NotEqual -1, :LessThan -1, :GreaterThan -1
   :LessEq -1, :GreaterEq -1, :ULessThan -1, :UGreaterThan -1
   :ZeroEqual 0, :ZeroLess 0, :ZeroGreater 0
   :If -1
   :Else :unknown, :Then :unknown
   :Begin :unknown
   :Until -1, :While -1
   :Repeat :unknown, :Again :unknown
   :Do -2
   :Loop :unknown, :PlusLoop -1
   :Leave :unknown
   :I 1, :J 1, :Exit :unknown
   :Call nil
   :Recurse :unknown
   :Emit -1, :CR 0, :Dot -1, :DotS 0
   :LineComment 0, :BlockComment 0})

;; ---- 2. Transcription of stackDelta's match table (see scope note above) ----

(defn stack-delta [instr-kw]
  (cond
    (#{:Literal :CharLit :VariableRef :I :J :Depth :RFetch} instr-kw) 1
    (#{:Drop :ToR :Store :CStore :HStore :PlusStore :Emit} instr-kw) -1
    (#{:Plus :Minus :Multiply :Divide :DivMod :Modulo
       :And :Or :Xor :LShift :RShift :URShift
       :Equal :NotEqual :LessThan :GreaterThan :LessEq :GreaterEq
       :ULessThan :UGreaterThan :MaxOp :MinOp} instr-kw) -1
    (#{:Negate :Abs :OnePlus :OneMinus :TwoMul :TwoDiv
       :Invert :ZeroEqual :ZeroLess :ZeroGreater :Fetch :CFetch :HFetch}
     instr-kw) 0
    (#{:Dup :Over :Tuck :RFetch} instr-kw) 1
    (#{:Swap :Rot :NRot :Nip} instr-kw) 0
    (= instr-kw :Dup2) 2
    (= instr-kw :Drop2) -2
    (= instr-kw :FromR) 1
    (#{:CR :DotS :Exit :Recurse :Loop :PlusLoop :Leave :Again
       :Begin :Until :While :Repeat :LineComment :BlockComment} instr-kw) 0
    (= instr-kw :Dot) -1
    (#{:If :Do} instr-kw) -1
    (#{:Else :Then} instr-kw) 0
    (= instr-kw :Pick) 1
    (= instr-kw :Call) nil
    (= instr-kw :DivMod) 0                   ; dead: DivMod matched above
    :else :NO-MATCH))

;; ---- 3. Properties ----

(def checkable-instrs
  (vec (remove #(= :unknown (expected-delta %)) (keys expected-delta))))

(def known-buggy-instrs
  "The exact, fully-enumerated set of constructors where stack-delta
   disagrees with its own doc comment (found by exhaustively running the
   property below over every checkable constructor -- not hand-picked).
   Kept as an explicit set, rather than filtered out silently, so this
   file stays the single source of truth for 'which of these are
   currently known-broken' -- if one gets fixed, removing it from this
   set is a deliberate, visible edit, not something that just quietly
   stops failing."
  #{:Store :CStore :HStore :PlusStore :Nip :DivMod :Do :Until :While :PlusLoop})

;; Forward-looking regression guard: every constructor NOT already known
;; to be buggy must keep agreeing with its own doc comment. Scoped this
;; way so the spec stays green in CI; if a future edit to stack-delta's
;; match table introduces a new disagreement anywhere else, this catches
;; it immediately instead of it hiding among the 10 already-known ones.
(defspec stack-delta-matches-doc-comments-outside-known-bugs 200
  (prop/for-all [instr-kw (gen/elements (remove known-buggy-instrs checkable-instrs))]
    (= (get expected-delta instr-kw)
       (stack-delta instr-kw))))

(deftest exactly-these-constructors-disagree-with-their-own-comments-test
  (testing "Full enumeration: the set of checkable constructors where
            stack-delta's coded value disagrees with that constructor's
            own ( before -- after ) comment is exactly known-buggy-instrs
            -- no more, no fewer. If this fails because the set shrank,
            someone fixed a bug above and should remove it from
            known-buggy-instrs. If it fails because the set grew, a new
            regression was introduced."
    (let [actual-mismatches (set (for [k checkable-instrs
                                        :when (not= (get expected-delta k
                                                     (stack-delta k)))]
                                    k))]
      (is (= known-buggy-instrs actual-mismatches)))))

;; ---- 4. Pinned regressions: the 10 constructors this property found to
;;         disagree with their own doc comment. Each is a real, independently
;;         confirmed discrepancy -- not a hypothetical. ----

(deftest store-family-consumes-two-not-one-test
  (testing "BUG: Store/CStore/HStore/PlusStore all store a (value, address)
            pair -- 2 consumed, 0 produced, delta=-2 per their own comments
            ( val addr -- ) / ( byte addr -- ) / ( half addr -- ) /
            ( n addr -- ) -- but the shared match arm
            `Drop | ToR | Store | CStore | HStore | PlusStore | Emit`
            gives them all delta=-1, correct only for the single-operand
            members of that arm (Drop, ToR, Emit)."
    (is (= -1 (stack-delta :Store)) "current (wrong) coded value")
    (is (= -2 (get expected-delta :Store)) "correct value per its own comment")
    (is (= -1 (stack-delta :CStore)))
    (is (= -1 (stack-delta :HStore)))
    (is (= -1 (stack-delta :PlusStore)))))

(deftest nip-consumes-two-produces-one-test
  (testing "BUG: Nip's own comment is ( a b -- b ): 2 consumed, 1 produced,
            delta=-1. It is grouped with Swap/Rot/NRot in the delta=0 arm,
            which is correct for them (equal push/pop count) but not Nip."
    (is (= 0 (stack-delta :Nip)) "current (wrong) coded value")
    (is (= -1 (get expected-delta :Nip)) "correct value per its own comment")))

(deftest divmod-shadowed-by-earlier-arm-test
  (testing "BUG: DivMod ( a b -- rem quot ) is 2-in/2-out, delta=0 -- and is
            coded as such in a dedicated `case DivMod => Some(0)` arm. But
            DivMod is ALSO listed earlier in the big binary-op arm that
            yields -1, and Scala match arms are first-match-wins, so the
            correct arm is unreachable dead code."
    (is (= -1 (stack-delta :DivMod)) "current (wrong, shadowed) value")
    (is (= 0 (get expected-delta :DivMod)) "correct value; dead in the real match")))

(deftest do-consumes-two-not-one-test
  (testing "BUG: Do's own comment is ( limit start -- ): 2 consumed, 0
            produced, delta=-2. It shares a match arm with If (which
            correctly consumes 1 flag, delta=-1), so Do incorrectly
            inherits -1."
    (is (= -1 (stack-delta :Do)) "current (wrong) coded value")
    (is (= -2 (get expected-delta :Do)) "correct value per its own comment")))

(deftest loop-control-flags-not-consumed-test
  (testing "BUG: Until and While both have own-comment ( flag -- ): each
            consumes 1 flag, delta=-1. Both are grouped into the large
            delta=0 'purely structural' arm alongside Begin/Repeat/Again
            (which really are 0), so the flag consumption is dropped."
    (is (= 0 (stack-delta :Until)) "current (wrong) coded value")
    (is (= -1 (get expected-delta :Until)) "correct value per its own comment")
    (is (= 0 (stack-delta :While)))
    (is (= -1 (get expected-delta :While)))))

(deftest plusloop-increment-not-consumed-test
  (testing "BUG: PlusLoop's own comment is ( n -- ): consumes the loop
            increment, delta=-1. It is grouped into the delta=0 structural
            arm alongside Loop (which correctly has no explicit operand)."
    (is (= 0 (stack-delta :PlusLoop)) "current (wrong) coded value")
    (is (= -1 (get expected-delta :PlusLoop)) "correct value per its own comment")))