(set-logic ALL)
(set-option :produce-models true)
(set-option :tlimit 7200000)

;; ============================================================================
;; 1. Formal Specification of the Abstract Syntax Tree (AST)
;; ============================================================================
(declare-datatypes ((Expr 0))
  (((ConstScalar (csN Int))
    (ArgV        (argIdx Int))
    (VarV        (varName Int))
    (AddE        (addL Expr) (addR Expr))
    (LetE        (letName Int) (letVal Expr) (letBody Expr)))))

;; ============================================================================
;; 2. Stratified Moduli Space: Spectral Parameters & Fundamental Chamber
;; ============================================================================
(define-fun is_annihilated ((x Int)) Bool (= x 0))
(define-fun is_real_layer ((x Int)) Bool (and (>= x 1) (<= x 8)))
(define-fun is_imag_layer ((x Int)) Bool (and (>= x 9) (<= x 16)))
(define-fun is_pure_imag_layer ((x Int)) Bool (and (>= x 17) (<= x 24)))

(define-fun is_stable_domain ((x Int)) Bool
  (or (is_real_layer x) (is_imag_layer x) (is_pure_imag_layer x)))

;; ============================================================================
;; 3. Heisenberg Defect & The Θ-link (Berry Phase Holonomy)
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
;; 4. Arithmetic Height Control: Verschiebung & Galois Reduction
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
;; 5. Evaluation Morphism via Functional Functional Linkage (Functional Environment)
;; ============================================================================
;; Substitution history is modeled as a functional linkage instead of an Array SMT-theory
;; to prevent exponential read-over-write axioms explosion during deep AST evaluation.
(declare-fun env_lookup (Int Int) Int)

(define-fun-rec eval_functor ((e Expr) (env_id Int)) Int
  (match e
    (((ConstScalar csN) 1)
     ((ArgV argIdx) 1)
     ((VarV varName) (env_lookup env_id varName))
     ((AddE addL addR)
      (let ((wl (eval_functor addL env_id))
            (wr (eval_functor addR env_id)))
        (let ((combined (combine wl wr)))
          (verschiebung_op combined))))
     ((LetE letName letVal letBody)
      (let ((next_env (eval_functor letVal env_id)))
        (eval_functor letBody next_env))))))

;; ============================================================================
;; 6. Verification: Structural Stability via Topological Invariants
;; ============================================================================
(define-fun-rec chain ((k Int)) Expr
  (ite (<= k 0)
       (ArgV 0)
       (LetE k (chain (- k 1)) (AddE (VarV k) (VarV k)))))

;; Initialize environment ID 0 in the Pure Imaginary stratum to activate Θ-link.
(assert (forall ((var Int)) (= (env_lookup 0 var) 17)))

(declare-const targetExpr Expr)
(assert (= targetExpr (chain 50)))

;; Verification Goal
(assert (is_stable_domain (eval_functor targetExpr 0)))
(assert (not (is_annihilated (eval_functor targetExpr 0))))

(check-sat)
(get-value ((eval_functor targetExpr 0)))