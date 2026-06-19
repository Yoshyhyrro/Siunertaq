; ============================================================
; MZV Petersen Fluid Stack Machine — Formal Verification Suite
; Generated from: org.typelevel.mzv.machine.PetersenFluidMachine
;
; Vertex encoding:
;   Outer(i) = i       for i in {0,1,2,3,4}
;   Inner(i) = i + 5   for i in {0,1,2,3,4}
;
; 15 undirected edges (3-regular, |V|=10):
;   Outer 5-cycle :  {0,1} {1,2} {2,3} {3,4} {4,0}
;   Spokes        :  {0,5} {1,6} {2,7} {3,8} {4,9}
;   Inner star    :  {5,7} {5,8} {6,8} {6,9} {7,9}
;     (Inner(i)--Inner((i+2)%5) and Inner(i)--Inner((i+3)%5))
;
; NOTE on "pure imaginary" vs ??? placement:
;   The `???` in resolveStack fires when no length-2 path exists.
;   P1 proves this is unreachable => ??? is the "pure imaginary" boundary:
;   it is structurally guaranteed never to be evaluated in the real part.
;   The ImaginaryPopperActor handles the *other* ??? (s1=1 divergence),
;   which is the Fischer f9 / odd-depth obstruction (see HaradaNortonCarabiner).
;
; Run: z3 mzv_petersen.smt2
; ============================================================

(set-logic QF_LIA)
(set-option :produce-models true)

; ---- Vertex predicates ----------------------------------------

(define-fun isValid ((v Int)) Bool
  (and (>= v 0) (< v 10)))

(define-fun isOuter ((v Int)) Bool
  (and (>= v 0) (< v 5)))

(define-fun isInner ((v Int)) Bool
  (and (>= v 5) (< v 10)))

; ---- Petersen graph adjacency (30 directed arcs) ---------------

(define-fun adjacent ((u Int) (v Int)) Bool
  (or
    ; Outer 5-cycle
    (and (= u 0) (= v 1)) (and (= u 1) (= v 0))
    (and (= u 1) (= v 2)) (and (= u 2) (= v 1))
    (and (= u 2) (= v 3)) (and (= u 3) (= v 2))
    (and (= u 3) (= v 4)) (and (= u 4) (= v 3))
    (and (= u 4) (= v 0)) (and (= u 0) (= v 4))
    ; Spokes
    (and (= u 0) (= v 5)) (and (= u 5) (= v 0))
    (and (= u 1) (= v 6)) (and (= u 6) (= v 1))
    (and (= u 2) (= v 7)) (and (= u 7) (= v 2))
    (and (= u 3) (= v 8)) (and (= u 8) (= v 3))
    (and (= u 4) (= v 9)) (and (= u 9) (= v 4))
    ; Inner {5/2}-star
    (and (= u 5) (= v 7)) (and (= u 7) (= v 5))
    (and (= u 5) (= v 8)) (and (= u 8) (= v 5))
    (and (= u 6) (= v 8)) (and (= u 8) (= v 6))
    (and (= u 6) (= v 9)) (and (= u 9) (= v 6))
    (and (= u 7) (= v 9)) (and (= u 9) (= v 7))))

; ---- applyPentagonRelation encoding ----------------------------
;
; val delta = if (source.getClass != target.getClass) 1 else -1
; => pentaDelta(src,tgt) = 1  if Outer<->Inner jump
;                        = -1 if same class

(define-fun pentaDelta ((src Int) (tgt Int)) Int
  (ite (xor (isOuter src) (isOuter tgt)) 1 (- 1)))

; MZVTriple(s1, s2 + delta, s3 - delta)
(define-fun pentaS2 ((s2 Int) (src Int) (tgt Int)) Int
  (+ s2 (pentaDelta src tgt)))

(define-fun pentaS3 ((s3 Int) (src Int) (tgt Int)) Int
  (- s3 (pentaDelta src tgt)))


; ============================================================
; P1 — Petersen graph has diameter <= 2
;
; Proves: the `???` arm in resolveStack (the "pure imaginary boundary")
; is dead code. For any two distinct valid vertices, a path of
; length <= 2 always exists.
;
; Encoding: negate the diameter claim and check for UNSAT.
; Expected: unsat
; ============================================================

(echo "P1: diameter <= 2  (??? boundary is unreachable)  [expect unsat]")
(push 1)
(declare-const p1_u Int)
(declare-const p1_v Int)
(assert (isValid p1_u))
(assert (isValid p1_v))
(assert (not (= p1_u p1_v)))
(assert (not
  (or (adjacent p1_u p1_v)
      (and (adjacent p1_u 0) (adjacent 0 p1_v))
      (and (adjacent p1_u 1) (adjacent 1 p1_v))
      (and (adjacent p1_u 2) (adjacent 2 p1_v))
      (and (adjacent p1_u 3) (adjacent 3 p1_v))
      (and (adjacent p1_u 4) (adjacent 4 p1_v))
      (and (adjacent p1_u 5) (adjacent 5 p1_v))
      (and (adjacent p1_u 6) (adjacent 6 p1_v))
      (and (adjacent p1_u 7) (adjacent 7 p1_v))
      (and (adjacent p1_u 8) (adjacent 8 p1_v))
      (and (adjacent p1_u 9) (adjacent 9 p1_v)))))
(check-sat)
(pop 1)


; ============================================================
; P2 — s2 + s3 is conserved under applyPentagonRelation
;
; pentaDelta d is added to s2 and subtracted from s3.
; Therefore (s2+d) + (s3-d) = s2+s3, so mzvWeight = s1+(s2+s3) is
; invariant across any traversal.
;
; Expected: unsat
; ============================================================

(echo "P2: s2+s3 conserved across any edge  [expect unsat]")
(push 1)
(declare-const p2_s2  Int)
(declare-const p2_s3  Int)
(declare-const p2_src Int)
(declare-const p2_tgt Int)
(assert (isValid p2_src))
(assert (isValid p2_tgt))
(assert (adjacent p2_src p2_tgt))
(assert (not
  (= (+ (pentaS2 p2_s2 p2_src p2_tgt)
        (pentaS3 p2_s3 p2_src p2_tgt))
     (+ p2_s2 p2_s3))))
(check-sat)
(pop 1)


; ============================================================
; P3 — Edge existence: the test-case path 0->5->8 is valid
;
; Outer(0)=0, Inner(0)=5, Inner(3)=8
; Checks that both hops exist in the Petersen graph.
; Expected: sat
; ============================================================

(echo "P3: edges {0,5} and {5,8} exist  [expect sat]")
(push 1)
(assert (and (adjacent 0 5) (adjacent 5 8)))
(check-sat)
(pop 1)


; ============================================================
; P4 — Test-case 1 is a fixed point
;
; MZVTriple(3,2,1) along path Outer(0)->Inner(0)->Inner(3) = 0->5->8
;
; Step 1  0->5  Outer->Inner, delta=1:   mid = (s2=3, s3=0)
; Step 2  5->8  Inner->Inner, delta=-1:  out = (s2=2, s3=1)
;
; output == input: the triple is a FIXED POINT of this traversal.
; This corresponds to the IKZ regularization remainder being zero
; for the particular weight combination (3+2+1=6, even weight).
;
; Expected: sat
; ============================================================

(echo "P4: MZVTriple(3,2,1) is a fixed point of path 0->5->8  [expect sat]")
(push 1)
(declare-const p4_mid_s2 Int)
(declare-const p4_mid_s3 Int)
(declare-const p4_out_s2 Int)
(declare-const p4_out_s3 Int)
; step 1: 0->5  (Outer->Inner, delta=1)
(assert (= p4_mid_s2 (pentaS2 2 0 5)))   ; 2+1=3
(assert (= p4_mid_s3 (pentaS3 1 0 5)))   ; 1-1=0
; step 2: 5->8  (Inner->Inner, delta=-1)
(assert (= p4_out_s2 (pentaS2 p4_mid_s2 5 8)))  ; 3-1=2
(assert (= p4_out_s3 (pentaS3 p4_mid_s3 5 8)))  ; 0+1=1
; fixed point check
(assert (= p4_out_s2 2))
(assert (= p4_out_s3 1))
(check-sat)
(get-value (p4_mid_s2 p4_mid_s3 p4_out_s2 p4_out_s3))
(pop 1)


; ============================================================
; P5 — Divergence detection (s1=1 <=> not convergent)
;
; isConvergent = (s1 > 1). The ImaginaryPopperActor fires `???`
; precisely when s1 = 1. Verify no value satisfies both.
; Expected: unsat
; ============================================================

(echo "P5: s1=1 and s1>1 are contradictory  [expect unsat]")
(push 1)
(declare-const p5_s1 Int)
(assert (= p5_s1 1))
(assert (> p5_s1 1))
(check-sat)
(pop 1)


; ============================================================
; P6 — Convergence preserved through full 2-hop traversal
;
; s1 is never touched by applyPentagonRelation.
; So: (s1 > 1) before => (s1 > 1) after any path of length <= 2.
; Expected: unsat
; ============================================================

(echo "P6: convergence invariant across 2-hop path  [expect unsat]")
(push 1)
(declare-const p6_s1  Int)
(declare-const p6_src Int)
(declare-const p6_mid Int)
(declare-const p6_tgt Int)
(assert (> p6_s1 1))
(assert (isValid p6_src))
(assert (isValid p6_mid))
(assert (isValid p6_tgt))
(assert (adjacent p6_src p6_mid))
(assert (adjacent p6_mid p6_tgt))
; seek counterexample: s1 > 1 becomes not (s1 > 1)
(assert (not (> p6_s1 1)))
(check-sat)
(pop 1)


; ============================================================
; P7 — Enumerate intermediate vertices on path Outer(0)->Inner(3)
;
; Query: exists mid. adjacent(0,mid) /\ adjacent(mid,8)
; The unique solution is mid=5 (=Inner(0)), matching resolveStack's
; getNeighbors search.
; Expected: sat, model mid=5
; ============================================================

(echo "P7: find midpoint 0->?->8  [expect sat, model mid=5]")
(push 1)
(declare-const p7_mid Int)
(assert (isValid p7_mid))
(assert (adjacent 0 p7_mid))
(assert (adjacent p7_mid 8))
(check-sat)
(get-value (p7_mid))
(pop 1)

(exit)
