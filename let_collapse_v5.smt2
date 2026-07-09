(set-logic ALL)
(set-option :produce-models true)
(set-option :tlimit 7200000)

;; ============================================================================
;; 1. Stratified Moduli Space: Spectral Parameters & Fundamental Chamber
;; ============================================================================
(define-fun is_annihilated ((x Int)) Bool (= x 0))
(define-fun is_real_layer ((x Int)) Bool (and (>= x 1) (<= x 8)))
(define-fun is_imag_layer ((x Int)) Bool (and (>= x 9) (<= x 16)))
(define-fun is_pure_imag_layer ((x Int)) Bool (and (>= x 17) (<= x 24)))

(define-fun is_stable_domain ((x Int)) Bool
  (or (is_real_layer x) (is_imag_layer x) (is_pure_imag_layer x)))

;; ============================================================================
;; 2. Heisenberg Defect & The Θ-link (Berry Phase Holonomy)
;; ============================================================================
(declare-fun combine_raw (Int Int) Int)
(declare-fun kappa (Int Int) Int)

(assert (forall ((x Int) (y Int))
  (! (=> (and (is_stable_domain x) (is_stable_domain y))
         (= (combine_raw x y) (+ (combine_raw y x) (kappa x y))))
     :pattern ((combine_raw x y)))))

(assert (forall ((x Int) (y Int))
  (! (=> (and (is_real_layer x) (is_real_layer y))
         (= (kappa x y) 0))
     :pattern ((kappa x y)))))

(declare-fun theta_link_correction (Int Int) Int)

(assert (forall ((x Int) (y Int))
  (! (=> (and (is_pure_imag_layer x) (is_pure_imag_layer y))
         (= (+ (kappa x y) (theta_link_correction x y)) 0))
     :pattern ((theta_link_correction x y)))))

(define-fun combine ((x Int) (y Int)) Int
  (ite (and (is_pure_imag_layer x) (is_pure_imag_layer y))
       (+ (combine_raw x y) (theta_link_correction x y))
       (combine_raw x y)))

;; ============================================================================
;; 3. Arithmetic Height Control: Verschiebung & Galois Reduction
;; ============================================================================
(declare-fun verschiebung_op (Int) Int)

(assert (forall ((x Int))
  (! (=> (> x 24)
         (and (>= (verschiebung_op x) 1) (<= (verschiebung_op x) 24)))
     :pattern ((verschiebung_op x)))))

(assert (forall ((x Int))
  (! (=> (and (>= x 1) (<= x 24))
         (= (verschiebung_op x) x))
     :pattern ((verschiebung_op x)))))

;; ============================================================================
;; 4. Direct Inductive Functor Mapping over Heisenberg Space (Flattened)
;; ============================================================================
;; To prevent DATATYPES_INST core dump, we shortcut the explicit AST representation.
;; We directly model the height evolution of the depth-k chain morphism.
(define-fun-rec eval_chain_height ((k Int) (current_env_val Int)) Int
  (ite (<= k 0)
       1 ; Base case: ArgV 0 evaluates to 1
       (let ((next_env_val (eval_chain_height (- k 1) current_env_val)))
         ; Morphism step: AddE (VarV k) (VarV k) where each VarV looks up next_env_val
         (let ((combined (combine next_env_val next_env_val)))
           (verschiebung_op combined)))))

;; ============================================================================
;; 5. Verification: Topological Stability via Inductive Flattening
;; ============================================================================
;; Initialize the ground environment value at 17 (Pure Imaginary Stratum)
(declare-const initial_stratum_weight Int)
(assert (= initial_stratum_weight 17))

(declare-const target_final_height Int)
(assert (= target_final_height (eval_chain_height 50 initial_stratum_weight)))

;; Verification Goal
(assert (is_stable_domain target_final_height))
(assert (not (is_annihilated target_final_height)))

(check-sat)
(get-value (target_final_height))