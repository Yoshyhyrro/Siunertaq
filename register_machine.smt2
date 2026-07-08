(set-logic ALL)

;; --- 1. Use Int as IDs for Registers and Values ---
;; This avoids strict sort-parameter parsing errors while maintaining abstract relations.

;; --- 2. Uninterpreted Function simulating Interactive/Functional Evaluation ---
;; Hides concrete arithmetic operators (+, *) to treat terms as pointers (DAG)
(declare-fun eval (Int Int) Int)
(declare-fun combine (Int Int) Int)

;; --- 3. Register Machine Instructions (Preserving DAG Pointer Sharing) ---
(declare-const r_a Int)
(declare-const r_x1 Int)
(declare-const r_x2 Int)
(declare-const r_x3 Int)

;; Instead of inline expansion, declare r_x1 as a symbolic combination of r_a and r_a
(assert (= r_x1 (combine r_a r_a)))
;; x2 = x1 + x1 (Maintains pointer sharing without letting the solver unroll it)
(assert (= r_x2 (combine r_x1 r_x1)))
(assert (= r_x3 (combine r_x2 r_x2))) 

;; --- 4. Lazy Evaluation (Apply only when the actual value is requested) ---
(declare-const base_val Int)
(assert (= (eval r_a base_val) base_val))

;; The solver checks satisfiability using the shared UF relations (DAG)
;; without flattening r_x3 into 2^3 instances of base_val
(check-sat)
(get-model)