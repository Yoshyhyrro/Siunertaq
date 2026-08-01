(ns siunertaq.batch-runner
  (:require [dhall-clj.core :as dhall]))

;; ==========================================
;; 1. JCL COND Statement Evaluation
;; ==========================================

(defn evaluate-cond [cond-val previous-rc abended?]
  "Evaluates the JCL COND statement to determine if a step should be bypassed.
   In JCL semantics: COND=(threshold, op). If 'threshold op previous-rc' is TRUE,
   the step is skipped. Returns true to execute, false to skip."
  (if-not cond-val
    true ;; No COND specified: execute unconditionally
    (let [;; Extract payload from Dhall union projection (handles potential map wrapping)
          expr (or (:Compare cond-val) (:Even cond-val) (:Only cond-val) cond-val)
          tag  (:tag expr)]
      (case tag
        "Even" true
        "Only" abended?
        "Compare"
        (let [threshold (:threshold expr)
              ;; Handle both raw string "LT" and map {"LT" {}} union representations from DhallJ
              op        (if (map? (:op expr)) (-> expr :op keys first name) (:op expr))]
          (not (case op
                 "LT" (< threshold previous-rc)
                 "LE" (<= threshold previous-rc)
                 "EQ" (= threshold previous-rc)
                 "NE" (not= threshold previous-rc)
                 "GT" (> threshold previous-rc)
                 "GE" (>= threshold previous-rc))))))))

;; ==========================================
;; 2. Stack Machine Instruction Runner
;; ==========================================

(defn execute-step! [step]
  "Mocks the execution of a single batch step, including its BSDQuiver stack machine instructions.
   Returns the resulting Return Code (RC)."
  (println (str "  [EXEC] Running step: " (:name step)))
  (println (str "         Target Vertex: " (:norm_vertex step)))
  (println (str "         Effect Tag:    " (:effect_tag step)))
  (println (str "         Instructions:  " (count (:input_prog step))))
  
  ;; In a real runtime, we would interpret the JSON payload of :input_prog here.
  ;; For this simulation, we assume successful execution (RC = 0).
  0)

;; ==========================================
;; 3. Batch Job Engine (State Traversal)
;; ==========================================

(defn run-job! [job-def]
  "Iterates through the steps in the batch job definition sequentially.
   Replaces the old Alexander-Hodge linked-list traversal with a stateful reducer for Job RC."
  (println "=== Starting Job:" (:job_name job-def) "| Prime:" (:prime job-def) "===")
  (let [steps (:steps job-def)]
    (loop [remaining-steps steps
           previous-rc     0
           abended?        false]
      (if (empty? remaining-steps)
        (println "=== Job Execution Completed ===")
        (let [current-step (first remaining-steps)
              cond-val     (:cond current-step)]
          (println (str "\nEvaluating step: [" (:name current-step) "] (Current RC=" previous-rc ")"))
          
          (if (evaluate-cond cond-val previous-rc abended?)
            (let [new-rc (execute-step! current-step)]
              (recur (rest remaining-steps) new-rc false))
            (do
              (println "  [SKIP] Bypassed due to COND evaluation.")
              (recur (rest remaining-steps) previous-rc abended?))))))))

;; ==========================================
;; 4. Execution Entry Point
;; ==========================================

(defn -main []
  (try
    (println "Loading Batch Job definition directly via dhall-clojure...")
    ;; Load and parse the Dhall configuration completely in-memory (no I/O piping needed)
    (let [job-def (dhall/load "./src/main/resources/BatchJob.dhall")]
      (run-job! job-def))
    (catch Exception e
      (println "Failed to load or execute job:" (.getMessage e)))))
