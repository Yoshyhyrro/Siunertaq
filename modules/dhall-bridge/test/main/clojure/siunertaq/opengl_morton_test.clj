(ns siunertaq.opengl-morton-test
  (:require [clojure.test :refer [deftest is testing]]
            [siunertaq.morton :as morton]
            [siunertaq.flock-diagnostics :as diag]))

;; ==========================================
;; 1. Morton Code (Z-Order Curve) Utilities
;; ==========================================
;; Moved to siunertaq.morton (src/main/clojure) so this logic is directly
;; reusable outside the test suite -- e.g. by a future rendering/engine
;; module -- rather than being trapped in test code.

;; ==========================================
;; 2. Mock Compute Shader AST & Execution Pipeline
;; ==========================================

(def mock-glsl-compute-shader
  "#version 430
   layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;
   
   struct TensorNode {
       uint morton_code;
       float val;
   };
   
   layout(std430, binding = 0) buffer TensorBuffer {
       TensorNode nodes[];
   };
   
   void main() {
       uint gid = gl_GlobalInvocationID.x;
       // GPU performs localized spatial processing based on Morton Code ordering
       nodes[gid].val = nodes[gid].val * 2.0;
   }")

;; ==========================================
;; 3. Tests
;; ==========================================

(deftest morton-code-spatial-locality-test
  (testing "Validates 3D Morton Code encoding accuracy for GPU buffer packing"
    ;; (0,0,0) -> 0
    (is (= 0 (morton/interleave-bits-3d 0 0 0)))
    ;; (1,0,0) -> bit 0 -> 1
    (is (= 1 (morton/interleave-bits-3d 1 0 0)))
    ;; (0,1,0) -> bit 1 -> 2
    (is (= 2 (morton/interleave-bits-3d 0 1 0)))
    ;; (0,0,1) -> bit 2 -> 4
    (is (= 4 (morton/interleave-bits-3d 0 0 1)))
    ;; (1,1,1) -> bits 0,1,2 -> 7
    (is (= 7 (morton/interleave-bits-3d 1 1 1)))))

(deftest opengl-compute-shader-dispatch-mock-test
  (testing "Simulates OpenGL SSBO buffer upload and Compute Shader execution over Morton-ordered data"
    (let [spatial-grid [{:coord [0 0 0] :val 1.5}
                        {:coord [1 0 0] :val 2.0}
                        {:coord [1 1 1] :val 3.5}]

          ;; 1. Transform spatial coordinates into Morton Code aligned buffer
          morton-packed-buffer (mapv (fn [{:keys [coord val]}]
                                       {:morton (apply morton/interleave-bits-3d coord)
                                        :val val})
                                     spatial-grid)

          ;; 2. Mock OpenGL glDispatchCompute execution
          gpu-processed-buffer (mapv (fn [node]
                                       (update node :val #(* % 2.0)))
                                     morton-packed-buffer)]

      (println "\n[OpenGL Mock] Compiling Compute Shader...")
      (println "[OpenGL Mock] Uploading Morton-indexed SSBO to VRAM...")
      (println "[OpenGL Mock] Executing glDispatchCompute(group_x=1, group_y=1, group_z=1)...")

      ;; Assert Morton indices are correctly sorted/ordered
      (is (= 0 (:morton (first morton-packed-buffer))))
      (is (= 7 (:morton (nth morton-packed-buffer 2))))

      ;; Assert Compute Shader multiply operation on GPU (each node's val * 2.0,
      ;; per the `nodes[gid].val = nodes[gid].val * 2.0;` shader body above)
      ;; index 0: (0,0,0) val=1.5 -> 1.5 * 2.0 = 3.0
      (is (= 3.0 (:val (first gpu-processed-buffer))))
      ;; index 2: (1,1,1) val=3.5 -> 3.5 * 2.0 = 7.0
      (is (= 7.0 (:val (nth gpu-processed-buffer 2))))

      ;; This spatial-grid has 3 entries. Per the flock-size threshold model,
      ;; 3 == d (min-distance) exactly, so it clears BW001 but is still
      ;; below k=5 (BW002) and below the erasure-margin of 9.
      (let [diagnostics (diag/diagnose-flock-size (count spatial-grid))]
        (is (= ["BW002" nil] (mapv :code diagnostics))
            "3-node SSBO buffer should warn (underconstrained election) and note (below erasure margin), but not error")))))

;; ==========================================
;; 4. Flock-Size Diagnostics (boidswarm threshold model port)
;; ==========================================
;; Ported from boidswarm's publicly documented [n,k,d]_q = [7,5,3]_5
;; threshold model (see siunertaq.flock-diagnostics docstring for scope
;; and licensing notes). These are the exact boundary values worked out
;; from that formula, so each assertion is independently verifiable by
;; hand rather than just mirroring the implementation.

(deftest flock-size-below-min-distance-test
  (testing "BW001: fewer than d=3 boids cannot form the correction graph"
    (is (= ["BW001"] (mapv :code (diag/diagnose-flock-size 1))))
    (is (= ["BW001"] (mapv :code (diag/diagnose-flock-size 2))))
    (is (= :error (:level (first (diag/diagnose-flock-size 1)))))))

(deftest flock-size-underconstrained-election-test
  (testing "BW002: d <= N < k=5 means election is underconstrained"
    ;; N=3: also 3 <= 9 (erasure-margin), so an erasure-margin note follows
    (is (= ["BW002" nil] (mapv :code (diag/diagnose-flock-size 3))))
    ;; N=4: additionally hits BW005, since 4 mod 5 = 4 = q-1
    (is (= ["BW002" "BW005" nil] (mapv :code (diag/diagnose-flock-size 4))))))

(deftest flock-size-weight-class-coverage-test
  (testing "BW003: k=5 <= N < n=7 means prime-leader role may go unfilled"
    (is (= ["BW003" nil] (mapv :code (diag/diagnose-flock-size 5))))
    ;; N=6: additionally hits BW004, since 6 mod 7 = 6 = n-1
    (is (= ["BW003" "BW004" nil] (mapv :code (diag/diagnose-flock-size 6))))))

(deftest flock-size-erasure-margin-test
  (testing "N >= n=7 clears the primary/structural/phase checks, but stays below the erasure-margin of 9 until it reaches 9"
    (is (= [nil] (mapv :code (diag/diagnose-flock-size 7))))
    (is (= [nil] (mapv :code (diag/diagnose-flock-size 8))))
    (is (= [] (diag/diagnose-flock-size 9))
        "N=9 = n + 2t clears every threshold, including the erasure margin")
    (is (= [] (diag/diagnose-flock-size 20))
        "Diagnostics stay clear well above the erasure margin")))