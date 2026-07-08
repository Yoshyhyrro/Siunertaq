(set-logic ALL)
(set-option :produce-models true)

;; --- 1. Use Int as IDs for Registers and Values ---

;; --- 2. Uninterpreted Function simulating Interactive/Functional Evaluation ---
(declare-fun eval (Int Int) Int)
(declare-fun combine (Int Int) Int)

;; --- 3. Register Machine Instructions (Preserving DAG Pointer Sharing) ---
(declare-const r_a Int)
(declare-const r_x1 Int)
(declare-const r_x2 Int)
(declare-const r_x3 Int)

(assert (= r_x1 (combine r_a r_a)))
(assert (= r_x2 (combine r_x1 r_x1)))
(assert (= r_x3 (combine r_x2 r_x2))) 

;; --- 4. Lazy Evaluation (Apply only when the actual value is requested) ---
(declare-const base_val Int)
(assert (= (eval r_a base_val) base_val))

(check-sat)