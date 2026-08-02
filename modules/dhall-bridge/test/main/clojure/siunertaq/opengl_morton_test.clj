(ns siunertaq.opengl-morton-test
  (:require [clojure.test :refer [deftest is testing]]))

;; ==========================================
;; 1. Morton Code (Z-Order Curve) Utilities
;; ==========================================

(defn interleave-bits-3d
  "Interleaves 10-bit integer coordinates (x, y, z) into a 30-bit Morton Code.
   Maintains spatial locality for GPU Shader SSBO access."
  [x y z]
  (letfn [(expand-bits [v]
            (let [v (bit-and v 0x000003ff)
                  v (bit-and (bit-or v (bit-shift-left v 16)) 0x030000ff)
                  v (bit-and (bit-or v (bit-shift-left v 8))  0x0300f00f)
                  v (bit-and (bit-or v (bit-shift-left v 4))  0x030c30c3)
                  v (bit-and (bit-or v (bit-shift-left v 2))  0x09249249)]
              v))]
    (bit-or (expand-bits x)
            (bit-shift-left (expand-bits y) 1)
            (bit-shift-left (expand-bits z) 2))))

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
    (is (= 0 (interleave-bits-3d 0 0 0)))
    ;; (1,0,0) -> bit 0 -> 1
    (is (= 1 (interleave-bits-3d 1 0 0)))
    ;; (0,1,0) -> bit 1 -> 2
    (is (= 2 (interleave-bits-3d 0 1 0)))
    ;; (0,0,1) -> bit 2 -> 4
    (is (= 4 (interleave-bits-3d 0 0 1)))
    ;; (1,1,1) -> bits 0,1,2 -> 7
    (is (= 7 (interleave-bits-3d 1 1 1)))))

(deftest opengl-compute-shader-dispatch-mock-test
  (testing "Simulates OpenGL SSBO buffer upload and Compute Shader execution over Morton-ordered data"
    (let [spatial-grid [{:coord [0 0 0] :val 1.5}
                        {:coord [1 0 0] :val 2.0}
                        {:coord [1 1 1] :val 3.5}]

          ;; 1. Transform spatial coordinates into Morton Code aligned buffer
          morton-packed-buffer (mapv (fn [{:keys [coord val]}]
                                       {:morton (apply interleave-bits-3d coord)
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

      ;; Assert Compute Shader multiply operation on GPU
      (is (= 3.0 (:val (first gpu-processed-buffer))))
      (is (= 7.0 (:val (nth gpu-processed-buffer 2)))))))