(ns siunertaq.batch-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [siunertaq.batch-runner :as runner]))

(deftest run-job-test
  (testing "Verifies job execution flow while mocking state transitions"
    ;; Rebind the execution function to isolate the state machine logic
    (with-redefs [runner/execute-step! (fn [step]
                                         ;; Yield a constant return code for deterministic testing
                                         0)]
      (let [mock-job {:job_name "MockJob"
                      :prime true
                      :steps [{:name "Step1" :cond nil}
                              {:name "Step2" :cond nil}]}]

        ;; Assert that the target function executes without throwing exceptions
        (is (nil? (runner/run-job! mock-job)))))))