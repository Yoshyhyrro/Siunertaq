;; Refactor namespace declaration to use clojure.java.shell and cheshire instead of dhall-clj
(ns siunertaq.batch-runner
  (:require [clojure.java.shell :as shell]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

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
              ;; Handle both raw string "LT" and map {"LT" {}} union representations
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
;; 4. Dhall Config Loader via Subprocess
;; ==========================================

(defn load-dhall-config [file-path]
  "Evaluates Dhall definition using external dhall-to-json process,
   returning parsed JSON structure as Clojure hash-map with keyword keys."
  (let [dhall-bin (or (System/getenv "DHALL_TO_JSON") "dhall-to-json")
        abs-file  (io/file file-path)
        {:keys [exit out err]} (shell/sh dhall-bin "--file" (.getAbsolutePath abs-file))]
    (if (zero? exit)
      (json/parse-string out true)
      (throw (ex-info (str "dhall-to-json execution failed (exit " exit ")")
                      {:exit-code exit
                       :error-msg err
                       :file file-path})))))


;; Helper to locate the resource file across different working directories
(defn resolve-dhall-path
  "Resolves the absolute path for BatchJob.dhall by checking potential candidate paths."
  []
  (let [candidate-paths ["src/main/resources/BatchJob.dhall"
                         "modules/dhall-bridge/src/main/resources/BatchJob.dhall"]]
    (some (fn [path]
            (let [file (io/file path)]
              (when (.exists file)
                (.getAbsolutePath file))))
          candidate-paths)))

;; ==========================================
;; 5. Execution Entry Point
;; ==========================================

(defn -main []
  (try
    (println "Loading Batch Job definition via external dhall-to-json CLI...")
    (if-let [dhall-path (resolve-dhall-path)]
      (let [job-def (load-dhall-config dhall-path)]
        (run-job! job-def))
      (println "Error: BatchJob.dhall not found in default candidate paths."))
    (catch Exception e
      (println "Failed to load or execute job:" (.getMessage e))
      (.printStackTrace e))))