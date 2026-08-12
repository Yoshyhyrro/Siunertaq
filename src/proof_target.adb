with Math_Types;   use Math_Types;
with Math_Program; use Math_Program;

package body Proof_Target is

   function Run_Simd_Dot_Product_Demo return Value is
   begin
      return Exec_Program (Simd_Dot_Product_Demo);
   end Run_Simd_Dot_Product_Demo;

end Proof_Target;