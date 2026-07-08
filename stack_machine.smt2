(set-logic ALL)

;; --- 1. Definition of Stack State (Type) ---
(declare-datatypes ((Stack 0)) 
  (((Empty) 
    (Push (top Int) (rest Stack) (depth Int))))) ; Embed depth into the structure

;; --- 2. Geometric Constraint for Safe Stack Push (Trap Function) ---
(define-fun safe-push ((val Int) (s Stack)) Stack
  (ite (>= (depth s) 8)
       Empty ; Blow up / collapse the stack to lock all subsequent evaluations
       (Push val s (+ (depth s) 1))))

;; --- 3. Simulation of Instruction Expansion ---
(declare-const s0 Stack)
(assert (= s0 Empty))

;; Chains of pushes rigged with the trap
(declare-const s1 Stack) (assert (= s1 (safe-push 10 s0)))
(declare-const s2 Stack) (assert (= s2 (safe-push 20 s1)))
(declare-const s3 Stack) (assert (= s3 (safe-push 30 s2)))
(declare-const s4 Stack) (assert (= s4 (safe-push 40 s3)))
(declare-const s5 Stack) (assert (= s5 (safe-push 50 s4)))
(declare-const s6 Stack) (assert (= s6 (safe-push 60 s5)))
(declare-const s7 Stack) (assert (= s7 (safe-push 70 s6)))
(declare-const s8 Stack) (assert (= s8 (safe-push 80 s7)))

;; This 9th push triggers the trap! s9 will be forced to equal Empty.
(declare-const s9 Stack) (assert (= s9 (safe-push 90 s8)))

;; --- 4. Verification Check ---
;; If the trap works, s9 is Empty, so its depth must be 0, NOT 9.
;; To prove this, we check if it's possible for s9 to have depth > 8.
;; The solver should instantly find this UNSATISFIABLE (UNSAT) because the trap locked it.
(assert (> (depth s9) 8))

(check-sat)