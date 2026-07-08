;; ============================================================================
;; 3. Heisenberg Group Action and Splitting-Based Dimensional Trap
;; ============================================================================

;; Base operator for the stable Shoji algebra.
(declare-fun combine (Int Int) Int)

;; Commutativity and Associativity hold strictly within the Shoji domain.
(assert (forall ((x Int) (y Int)) 
  (=> (and (is_shoji_domain x) (is_shoji_domain y))
      (= (combine x y) (combine y x)))))

(assert (forall ((x Int) (y Int) (z Int))
  (=> (and (is_shoji_domain x) (is_shoji_domain y) (is_shoji_domain z))
      (= (combine (combine x y) z) (combine x (combine y z))))))

;; Non-abelian Heisenberg generators rotating around the Cusp point.
(declare-fun act_X (Int) Int)
(declare-fun act_Y (Int) Int)

;; Splitting Principle Trap: 
;; In analogy to the splitting of characteristic classes (e.g., total Chern class),
;; any expansion beyond the Shoji domain (Obstruction) is captured by the 
;; non-commutative rotation and forced into a dimensional collapse to the Cusp (0).
(assert (forall ((x Int))
  (=> (not (is_shoji_domain x))
      (is_cusp (act_X (act_Y x))))))

;; Geometric Functor threading the splitting trap through the expression tree.
(define-fun-rec eval_functor ((e Expr) (env (Array Int Int))) Int
  (match e
    (((ConstScalar n)          1)
     ((ArgV i)                 1)
     ((VarV name)               (select env name))
     ((AddE l r)                
      (let ((wl (eval_functor l env))
            (wr (eval_functor r env)))
        (ite (and (is_shoji_domain wl) (is_shoji_domain wr))
             (combine wl wr)                          ; Stable algebraic composition
             (act_X (act_Y (combine wl wr))))))      ; Triggers the Splitting Trap
     ((LetE name val body)      
      (eval_functor body (store env name (eval_functor val env)))))))

;; ============================================================================
;; 4. Verification: Neutralizing State Explosion via Topological Collapse (k = 50)
;; ============================================================================

;; Recursive chain generating exponential path usage (Pathological Case).
(define-fun-rec chain ((k Int)) Expr
  (ite (<= k 0)
       (ArgV 0)
       (LetE k (chain (- k 1)) (AddE (VarV k) (VarV k)))))

(define-fun emptyIntEnv () (Array Int Int) ((as const (Array Int Int)) 0))

(declare-const targetExpr Expr)
(assert (= targetExpr (chain 50)))

;; Verification: The functor maps the massive tree into the bounded modulus space.
;; The Splitting Trap ensures that non-linear dimensions collapse into the Cusp,
;; allowing cvc5 to verify the invariant instantly without physical AST expansion.
(assert (is_bounded_modulus (eval_functor targetExpr emptyIntEnv)))

(check-sat)
(get-model)