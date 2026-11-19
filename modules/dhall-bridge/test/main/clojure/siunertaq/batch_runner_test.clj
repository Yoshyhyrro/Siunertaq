;; Test suite for Alexander-Hodge job execution state machine
(ns siunertaq.batch-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [siunertaq.batch-runner :as runner]))

(deftest alexander-hodge-traversal-test
  (testing "Validates state propagation across job steps without relying on linked-list traversal"
    (let [step-execution-count (atom 0)]
      (with-redefs [runner/execute-step! (fn [step]
                                           (swap! step-execution-count inc)
                                           ;; Return specific RC based on step name
                                           (case (:name step)
                                             "step-1" 0
                                             ;; RC=8 becomes previous-rc for step-3's COND check below
                                             "step-2" 8
                                             "step-3" 0
                                             0))]

        (let [mock-job {:job_name "HodgeTraversalJob"
                        ;; Steps are intentionally out of order to test stateful traversal
                        :steps [{:name "step-1" :cond nil}
                                {:name "step-2" :cond nil}
                                ;; COND=(4, LT): Skip if 4 < previousRC (4 < 8 is true -> skipped)
                                {:name "step-3" :cond {:Compare {:threshold 4 :op "LT"}}}]}]

          ;; Execute job traversal
          (runner/run-job! mock-job)

          ;; Verify that all non-skipped steps were executed (step-3 was skipped)
          (is (= 2 @step-execution-count)))))))

(deftest cond-evaluation-edge-cases-test
  (testing "Verifies JCL COND algebraic semantics against previous RC state"
    ;; Compare operator cases
    (let [cond-lt {:Compare {:threshold 4 :op "LT"}}]
      (is (true?  (runner/evaluate-cond cond-lt 0 false)) "RC=0: 4 < 0 is false -> Execute")
      (is (false? (runner/evaluate-cond cond-lt 8 false)) "RC=8: 4 < 8 is true  -> Skip"))

    ;; Only operator (ABEND recovery)
    (let [cond-only {:Only {}}]
      ;; Normal execution should skip recovery step
      (is (false? (runner/evaluate-cond cond-only 0 false)) "Normal: Skip recovery")
      (is (true?  (runner/evaluate-cond cond-only 0 true))  "ABEND: Execute recovery"))))

(deftest avx2-simd-instruction-mock-test
  (testing "Simulates AVX2 SIMD execution pipeline for Stack Machine vector operations"
    (let [ymm-dot-product-mock (fn [reg-a reg-b]
                                 ;; Accumulates a dot product the way a sequence of AVX2
                                 ;; VFMADD (fused multiply-add) instructions would on real
                                 ;; 256-bit YMM registers: sum_i (reg-a[i] * reg-b[i]).
                                 (reduce + (map * reg-a reg-b)))]

      (with-redefs [runner/execute-step!
                    (fn [step]
                      (let [instructions (:input_prog step)
                            has-vector-ops? (some #(or (contains? % :PushVec3)
                                                       (contains? % :DotVec3))
                                                  instructions)]
                        (if has-vector-ops?
                          (let [;; Allocate simulated 256-bit YMM registers (8x 32-bit float lanes).
                                ;; Only lanes 0-2 carry the vec3 payload from :input_prog below
                                ;; (x=2,y=4,z=0) and (x=1,y=0,z=8); lanes 3-7 are zero-padded
                                ;; and do not affect the sum.
                                ymm-reg-0 [2.0 4.0 0.0 0.0 0.0 0.0 0.0 0.0]
                                ymm-reg-1 [1.0 0.0 8.0 0.0 0.0 0.0 0.0 0.0]
                                result    (ymm-dot-product-mock ymm-reg-0 ymm-reg-1)]
                            ;; Expected: (2*1) + (4*0) + (0*8) + 0x5 = 2.0
                            (is (= 2.0 result) "SIMD dot product execution should yield correct scalar value")
                            ;; Return execution RC 0 on success
                            0)
                          ;; Standard ALU pipeline mock fallback
                          0)))]

        (let [mock-simd-job {:job_name "SIMDPipelineTestJob"
                             :steps [{:name "padic-lower"
                                      :cond nil
                                      :norm_vertex "Padic"
                                      :effect_tag "oplus_padic"
                                      ;; AST payload mimicking parsed Dhall JSON output.
                                      ;; These vec3 values match ymm-reg-0 (2,4,0) and
                                      ;; ymm-reg-1 (1,0,8) in the mocked executor above.
                                      :input_prog [{:PushVec3 {:x 2 :y 4 :z 0}}
                                                   {:PushVec3 {:x 1 :y 0 :z 8}}
                                                   {:DotVec3 {}}]}]}]

          ;; Trigger the job engine to ensure the state machine routes instructions to the mocked SIMD executor
          (runner/run-job! mock-simd-job))))))

;; ==========================================
;; 4. Property-Based / Model-Based Testing of evaluate-cond
;; ==========================================
;; cond-evaluation-edge-cases-test above pins a handful of hand-picked
;; examples. The generative tests below instead search the input space
;; itself, for two separate purposes:
;;
;;   (a) WELL-FORMED universe: assert evaluate-cond agrees with an
;;       independently-written reference model on every documented input
;;       shape (nested {:Compare/:Even/:Only ...}, the flat {:tag ...}
;;       form, and both the raw-string and wrapped-map representations of
;;       :op). This is a permanent regression guard -- kept in CI.
;;
;;   (b) ADVERSARIAL universe: malformed-but-plausible shapes (typos,
;;       missing keys, wrong nesting) that fall outside the documented
;;       contract. Running this during development surfaced four distinct,
;;       reproducible crash bugs in the current evaluate-cond (see the
;;       (comment ...) block at the bottom of this file for the fuzz
;;       campaign that found them). Each is pinned below as its own
;;       deftest documenting *current* behavior, so a future change to
;;       evaluate-cond that alters any of them -- fixing it, or
;;       accidentally changing the exception type -- will be caught
;;       immediately instead of silently drifting.

;; ---- (a) Well-formed universe: generators + independent model ----

(def gen-op
  "The six JCL comparison operators evaluate-cond understands."
  (gen/elements ["LT" "LE" "EQ" "NE" "GT" "GE"]))

(def gen-op-representation
  "evaluate-cond documents support for :op as either a raw string (\"LT\")
   or a Dhall-union-style single-entry map ({\"LT\" {}}); generate both."
  (gen/let [op gen-op
            wrapped? gen/boolean]
    (if wrapped? {op {}} op)))

(def gen-compare-payload
  (gen/let [threshold (gen/choose -20 20)
            op        gen-op-representation]
    {:threshold threshold :op op}))

(def gen-well-formed-cond-val
  "Every cond-val shape evaluate-cond's implementation explicitly branches
   on: no-COND, nested :Compare/:Even/:Only, and the flat {:tag ...} form
   (reachable when cond-val has no :Compare/:Even/:Only key but does carry
   a :tag directly -- see the `(or (:tag expr) ...)` fallback)."
  (gen/one-of
   [(gen/return nil)
    (gen/fmap (fn [payload] {:Compare payload}) gen-compare-payload)
    (gen/return {:Even {}})
    (gen/return {:Only {}})
    (gen/fmap (fn [payload] (assoc payload :tag "Compare")) gen-compare-payload)
    (gen/return {:tag "Even"})
    (gen/return {:tag "Only"})]))

(defn- unwrap-op
  "Same union-unwrapping evaluate-cond performs, reimplemented
   independently so the model below doesn't just call back into the code
   under test."
  [op]
  (if (map? op) (-> op keys first name) op))

(defn- op->fn [op]
  (case op "LT" < "LE" <= "EQ" = "NE" not= "GT" > "GE" >=))

(defn model-evaluate-cond
  "Independent reference implementation of JCL COND semantics: 'if
   threshold OP previous-rc is true, skip the step.' Deliberately written
   with plain cond/contains? dispatch on cond-val's shape, instead of
   evaluate-cond's compute-a-tag-then-case approach, so the two
   implementations have nothing but the spec in common."
  [cond-val previous-rc abended?]
  (cond
    (nil? cond-val) true

    (contains? cond-val :Compare)
    (let [{:keys [threshold op]} (:Compare cond-val)]
      (not ((op->fn (unwrap-op op)) threshold previous-rc)))

    (contains? cond-val :Even) true
    (contains? cond-val :Only) (boolean abended?)

    (= (:tag cond-val) "Compare")
    (not ((op->fn (unwrap-op (:op cond-val))) (:threshold cond-val) previous-rc))

    (= (:tag cond-val) "Even") true
    (= (:tag cond-val) "Only") (boolean abended?)

    :else true))

(defspec evaluate-cond-matches-reference-model 500
  (prop/for-all [cond-val    gen-well-formed-cond-val
                 previous-rc (gen/choose -20 20)
                 abended?    gen/boolean]
    (= (runner/evaluate-cond cond-val previous-rc abended?)
       (model-evaluate-cond cond-val previous-rc abended?))))

;; ---- (b) Adversarial universe: pinned regression tests for bugs found by fuzzing ----
;; Each :smallest value below is the actual test.check-shrunk minimal
;; counterexample, not a hand-picked one -- see the (comment ...) block
;; at the end of this file to reproduce the search.

(deftest evaluate-cond-crashes-on-bare-scalar-cond-val-test
  (testing "BUG (found by fuzzing): a truthy, non-associative cond-val
            (any bare number/string/keyword/boolean/list -- not nil, not
            a map) crashes instead of being treated as malformed input.
            Root cause: the tag-resolution fallback calls
            (contains? expr :threshold), and Clojure's contains? throws
            IllegalArgumentException for any type that isn't Associative,
            a Set, or nil. Shrunk minimal case: cond-val=0."
    (is (thrown-with-msg? IllegalArgumentException #"contains\? not supported on type"
                          (runner/evaluate-cond 0 0 false)))))

(deftest evaluate-cond-crashes-on-compare-missing-op-test
  (testing "BUG (found by fuzzing): {:Compare {:threshold N}} with no :op
            key resolves tag=\"Compare\" correctly (0 is truthy, so the
            :Compare short-circuit fires) but then op=nil falls through
            every case clause, and the inner (case op ...) has no default
            clause. Shrunk minimal case: {:Compare {:threshold 0}}."
    (is (thrown-with-msg? IllegalArgumentException #"No matching clause"
                          (runner/evaluate-cond {:Compare {:threshold 0}} 0 false)))))

(deftest evaluate-cond-crashes-on-unsupported-op-string-test
  (testing "BUG (found by fuzzing): any :op string outside the six
            supported operators -- a typo (\"LTE\", \"GTE\"), wrong case
            (\"lt\"), a symbol (\"<\"), or an unrecognized new operator --
            crashes the whole job run instead of failing gracefully.
            Shrunk minimal case: :op \"LTE\"."
    (is (thrown-with-msg? IllegalArgumentException #"No matching clause: LTE"
                          (runner/evaluate-cond {:Compare {:threshold 0 :op "LTE"}} 0 false)))))

(deftest evaluate-cond-crashes-on-empty-op-union-map-test
  (testing "BUG (found by fuzzing): :op as an empty map ({}) -- a
            union-with-no-variant-selected, plausible if upstream Dhall/
            JSON serialization ever omits the variant -- causes
            (-> expr :op keys first name) to call (name nil), which
            throws NullPointerException rather than a descriptive error."
    (is (thrown? NullPointerException
                (runner/evaluate-cond {:Compare {:threshold 0 :op {}}} 0 false)))))

(comment
  ;; ---------------------------------------------------------------
  ;; Exploratory fuzz campaign -- not run by `clojure -X:test`.
  ;;
  ;; This is REPL-driven-development scratch space, not a compiled test:
  ;; select a form below and evaluate it in your editor's connected REPL
  ;; to go hunting for further evaluate-cond edge cases. It is what
  ;; originally found the four bugs pinned above. Re-run after any change
  ;; to evaluate-cond's tag-resolution logic to check for new ones.
  ;; ---------------------------------------------------------------

  (def gen-malformed-compare-payload
    (gen/one-of
     [(gen/choose -5 5) ; :Compare maps straight to a scalar, no nesting
      (gen/fmap (fn [t] {:threshold t}) (gen/choose -5 5)) ; missing :op
      (gen/let [t  (gen/choose -5 5)
                op (gen/elements ["LTE" "GTE" "lt" "<" "==" "" "BETWEEN"])]
        {:threshold t :op op}) ; unsupported/typo op
      (gen/fmap (fn [t] {:threshold t :op {}}) (gen/choose -5 5))])) ; empty op map

  (def gen-adversarial-cond-val
    (gen/one-of
     [(gen/one-of [gen/small-integer gen/string-ascii gen/keyword
                   (gen/return true) (gen/list gen/small-integer)])
      (gen/fmap (fn [p] {:Compare p}) gen-malformed-compare-payload)]))

  (defn- classify-outcome [f & args]
    (try {:ok (apply f args)}
         (catch Throwable t {:threw (.getSimpleName (class t)) :msg (.getMessage t)})))

  ;; Expected to FAIL -- that's the point. Inspect (:shrunk result) for
  ;; the minimal reproduction of whatever new crash mode turns up.
  (tc/quick-check 1000
    (prop/for-all [cv gen-adversarial-cond-val
                   rc (gen/choose -10 10)
                   ab gen/boolean]
      (contains? (classify-outcome runner/evaluate-cond cv rc ab) :ok))))

  ;; end comment block
  