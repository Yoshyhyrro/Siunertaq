;; Test suite for Alexander-Hodge job execution state machine
(ns siunertaq.batch-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [siunertaq.batch-runner :as runner]))

(deftest alexander-hodge-traversal-test
  (testing "Validates state propagation across job steps without relying on linked-list traversal"
    ;; Mock step execution to simulate different return codes
    (let [step-execution-count (atom 0)]
      (with-redefs [runner/execute-step! (fn [step]
                                           (swap! step-execution-count inc)
                                           ;; Return specific RC based on step name
                                           (case (:name step)
                                             "step-1" 0
                                             "step-2" 8  ; Causes step-3 to skip
                                             "step-3" 0
                                             0))]

        (let [mock-job {:job_name "HodgeTraversalJob"
                        :steps [{:name "step-1" :cond nil}
                                ;; COND=(4, LT): Skip if 4 < previousRC (4 < 8 is true -> skipped)
                                {:name "step-3" :cond {:Compare {:threshold 4 :op "LT"}}}
                                {:name "step-2" :cond nil}]}]

          ;; Execute job traversal
          (runner/run-job! mock-job)

          ;; Verify that all non-skipped steps were executed (step-3 was skipped)
          (is (= 2 @step-execution-count)))))))

(deftest cond-evaluation-edge-cases-test
  (testing "Verifies JCL COND algebraic semantics against previous RC state"
    ;; Compare operator cases
    (let [cond-lt {:Compare {:threshold 4 :op "LT"}}]
      ;; COND=(4, LT) -> skip if 4 < RC
      (is (true?  (runner/evaluate-cond cond-lt 0 false)) "RC=0: 4 < 0 is false -> Execute")
      (is (false? (runner/evaluate-cond cond-lt 8 false)) "RC=8: 4 < 8 is true  -> Skip"))

    ;; Only operator (ABEND recovery)
    (let [cond-only {:Only {}}]
      (is (false? (runner/evaluate-cond cond-only 0 false) "Normal: Skip recovery"))
      (is (true?  (runner/evaluate-cond cond-only 0 true)  "ABEND: Execute recovery")))))