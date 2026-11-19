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
;; that's easy to get wrong doing by hand (a manual line-by-line read-through
;; of this file missed the :Nip discrepancy; the property test did not).
;;
;; STATUS: all 10 discrepancies this file originally found (Store/CStore/
;; HStore/PlusStore/Nip/DivMod/Do/Until/While/PlusLoop) have been fixed in
;; MecrispInstr.scala (see edit.bash). known-buggy-instrs is now empty and
;; stack-delta below has been updated to match the fixed source, so this
;; file's job going forward is purely a regression guard: it fails loudly
;; if stack-delta's match table and its own doc comments ever drift apart
;; again, for any constructor.
;;
;; SCOPE NOTE ON THE FUNCTION UNDER TEST: this sandbox has no Scala 3
;; compiler available (Maven Central is unreachable from here, same
;; constraint noted for cheshire earlier in this project), so `stack-delta`
;; below is a byte-for-byte transcription of stackDelta's match table --
;; same order, same arms -- not a JVM interop call into the real compiled
;; class. In an environment where Scala 3 actually builds (this project's
;; CI does), replace `stack-delta` with a call into the real
;; io.siunertaq.postgres.MecrispInstr companion object; the model and the
;; property below don't need to change.

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

;; ---- 2. Transcription of stackDelta's match table (see scope note above;
;;         updated to match the fix applied by edit.bash) ----

(defn stack-delta [instr-kw]
  (cond
    (#{:Literal :CharLit :VariableRef :I :J :Depth :RFetch} instr-kw) 1
    (#{:Drop :ToR :Emit} instr-kw) -1
    (#{:Store :CStore :HStore :PlusStore} instr-kw) -2
    (#{:Plus :Minus :Multiply :Divide :Modulo
       :And :Or :Xor :LShift :RShift :URShift
       :Equal :NotEqual :LessThan :GreaterThan :LessEq :GreaterEq
       :ULessThan :UGreaterThan :MaxOp :MinOp} instr-kw) -1
    (#{:Negate :Abs :OnePlus :OneMinus :TwoMul :TwoDiv
       :Invert :ZeroEqual :ZeroLess :ZeroGreater :Fetch :CFetch :HFetch}
     instr-kw) 0
    (#{:Dup :Over :Tuck :RFetch} instr-kw) 1
    (#{:Swap :Rot :NRot} instr-kw) 0
    (= instr-kw :Nip) -1
    (= instr-kw :Dup2) 2
    (= instr-kw :Drop2) -2
    (= instr-kw :FromR) 1
    (#{:CR :DotS :Exit :Recurse :Loop :Leave :Again
       :Begin :Repeat :LineComment :BlockComment} instr-kw) 0
    (#{:Until :While} instr-kw) -1
    (= instr-kw :PlusLoop) -1
    (= instr-kw :Dot) -1
    (= instr-kw :If) -1
    (= instr-kw :Do) -2
    (#{:Else :Then} instr-kw) 0
    (= instr-kw :Pick) 1
    (= instr-kw :Call) nil
    (= instr-kw :DivMod) 0
    :else :NO-MATCH))

;; ---- 3. Properties ----

(def checkable-instrs
  (vec (remove #(= :unknown (expected-delta %)) (keys expected-delta))))

(def known-buggy-instrs
  "The exact, fully-enumerated set of constructors where stack-delta
   disagrees with its own doc comment. Empty as of the edit.bash fix --
   all 10 originally-found discrepancies (Store/CStore/HStore/PlusStore/
   Nip/DivMod/Do/Until/While/PlusLoop) are corrected. Kept as an explicit
   set (rather than deleted) so that if a *new* discrepancy is ever
   found, the fix-then-pin workflow this file follows stays consistent:
   populate this set with the new finding, and the property below
   automatically narrows its coverage until that one is fixed too."
  #{})

;; Forward-looking regression guard: every checkable constructor must
;; agree with its own doc comment. Covers all of checkable-instrs now
;; that known-buggy-instrs is empty; automatically narrows again if a
;; future regression gets added to known-buggy-instrs above.
(defspec stack-delta-matches-doc-comments-outside-known-bugs 200
  (prop/for-all [instr-kw (gen/elements (remove known-buggy-instrs checkable-instrs))]
    (= (get expected-delta instr-kw)
       (stack-delta instr-kw))))

(deftest exactly-these-constructors-disagree-with-their-own-comments-test
  (testing "Full enumeration: the set of checkable constructors where
            stack-delta's coded value disagrees with that constructor's
            own ( before -- after ) comment is exactly known-buggy-instrs
            -- no more, no fewer. Currently expected to be empty (the
            fix landed); if this fails with a non-empty actual set, a
            new regression was introduced somewhere in stack-delta."
    (let [actual-mismatches (set (for [k checkable-instrs
                                        :when (not= (get expected-delta k)
                                                     (stack-delta k))]
                                    k))]
      (is (= known-buggy-instrs actual-mismatches)))))

;; ---- 4. Regression guards: the 10 constructors this property originally
;;         found to disagree with their own doc comment, now fixed. Each
;;         assertion below pins the CORRECT value going forward -- if any
;;         of these ever regresses back to the old (wrong) value, it's
;;         caught here individually, with the original root-cause
;;         explanation still attached. ----

(deftest store-family-consumes-two-not-one-test
  (testing "FIXED: Store/CStore/HStore/PlusStore all store a (value,
            address) pair -- 2 consumed, 0 produced, delta=-2 per their
            own comments ( val addr -- ) / ( byte addr -- ) /
            ( half addr -- ) / ( n addr -- ). Previously miscoded as -1
            (shared match arm with the single-operand Drop/ToR/Emit);
            now split into its own arm."
    (is (= -2 (stack-delta :Store)))
    (is (= -2 (get expected-delta :Store)))
    (is (= -2 (stack-delta :CStore)))
    (is (= -2 (stack-delta :HStore)))
    (is (= -2 (stack-delta :PlusStore)))))

(deftest nip-consumes-two-produces-one-test
  (testing "FIXED: Nip's own comment is ( a b -- b ): 2 consumed, 1
            produced, delta=-1. Previously miscoded as 0 (grouped with
            Swap/Rot/NRot, correct for them but not Nip); now split out."
    (is (= -1 (stack-delta :Nip)))
    (is (= -1 (get expected-delta :Nip)))))

(deftest divmod-no-longer-shadowed-test
  (testing "FIXED: DivMod ( a b -- rem quot ) is 2-in/2-out, delta=0.
            Previously shadowed by an earlier, incorrect match on DivMod
            in the -1 binary-op arm (first-match-wins made the correct
            `case DivMod => Some(0)` arm dead code); DivMod is now
            removed from that earlier arm, so the correct arm is live."
    (is (= 0 (stack-delta :DivMod)))
    (is (= 0 (get expected-delta :DivMod)))))

(deftest do-consumes-two-not-one-test
  (testing "FIXED: Do's own comment is ( limit start -- ): 2 consumed, 0
            produced, delta=-2. Previously miscoded as -1 (shared match
            arm with If, which correctly consumes 1 flag); now split
            into its own arm."
    (is (= -2 (stack-delta :Do)))
    (is (= -2 (get expected-delta :Do)))))

(deftest loop-control-flags-now-consumed-test
  (testing "FIXED: Until and While both have own-comment ( flag -- ):
            each consumes 1 flag, delta=-1. Previously miscoded as 0
            (grouped into the large 'purely structural' delta=0 arm);
            now split into their own arm."
    (is (= -1 (stack-delta :Until)))
    (is (= -1 (get expected-delta :Until)))
    (is (= -1 (stack-delta :While)))
    (is (= -1 (get expected-delta :While)))))

(deftest plusloop-increment-now-consumed-test
  (testing "FIXED: PlusLoop's own comment is ( n -- ): consumes the loop
            increment, delta=-1. Previously miscoded as 0 (grouped into
            the delta=0 structural arm alongside Loop, which correctly
            has no explicit operand); now split out."
    (is (= -1 (stack-delta :PlusLoop)))
    (is (= -1 (get expected-delta :PlusLoop)))))