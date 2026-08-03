with Math_Types;   use Math_Types;
with Math_Program; use Math_Program;

package Proof_Target with SPARK_Mode is

   --  Concrete regression/proof target: the exact PushVec3/PushVec3/
   --  DotVec3 program used by the AVX2 SIMD mock in
   --  test/main/clojure/siunertaq/batch_runner_test.clj
   --  (ymm-reg-0 = (2,4,0), ymm-reg-1 = (1,0,8),
   --   dot = 2*1 + 4*0 + 0*8 = 2), re-expressed here as a
   --  Math_Program.Instr_Array so the same arithmetic is pinned
   --  independently in a third language/toolchain.
   Simd_Dot_Product_Demo : constant Instr_Array :=
     (1 => (Kind => Push_Vec3, Vec_X => 2, Vec_Y => 4, Vec_Z => 0),
      2 => (Kind => Push_Vec3, Vec_X => 1, Vec_Y => 0, Vec_Z => 8),
      3 => (Kind => Dot_Vec3));

   --  Runs Simd_Dot_Product_Demo through Exec_Program. The Post aspect
   --  below is the actual proof target: under gnatprove, this is a
   --  formal claim that Math_Program's semantics agree with the
   --  Clojure-side mock on this concrete input, not merely something
   --  observed once at run time. (This sandbox only has plain GNAT, so
   --  what is verified here is compilation, exhaustiveness, and a
   --  runtime-checked assertion of the same Post -- see
   --  proof_target_demo.adb. A full static discharge of this Post
   --  needs gnatprove, which is not installed in this environment.)
   function Run_Simd_Dot_Product_Demo return Value
     with Post => Run_Simd_Dot_Product_Demo'Result.Kind = Scalar
                  and then Run_Simd_Dot_Product_Demo'Result.N = 2;

end Proof_Target;