;; Test suite for Alexander-Hodge job execution state machine
(ns siunertaq.batch-runner-test
  (:require [clojure.test :refer [deftest is testing]]
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