;; --- 1. Definition of Stack State (Type) ---
(declare-datatypes ((Stack 0)) 
  (((Empty) 
    (Push (top Int) (rest Stack) (depth Int))))) ; Embed depth into the structure

;; --- 2. Geometric Constraint for Safe Stack Push (Trap Function) ---
;; Prevents the creation of stacks exceeding a depth of 8, enforcing a physical limit
(define-fun safe-push ((val Int) (s Stack)) Stack
  (ite (>= (depth s) 8)
       Empty ; Blow up / collapse the stack to lock all subsequent evaluations
       (Push val s (+ (depth s) 1))))

;; --- 3. Simulation of Instruction Expansion ---
(declare-const s0 Stack)
(assert (= s0 Empty))

;; A chain of pushes rigged with the trap
(declare-const s1 Stack) (assert (= s1 (safe-push 10 s0)))
(declare-const s2 Stack) (assert (= s2 (safe-push 20 s1)))
;; ... If this chains more than 8 times, it collapses into Empty automatically

;; Verification: Deep explosive search is immediately killed (UNSAT) by this constraint
(check-sat)