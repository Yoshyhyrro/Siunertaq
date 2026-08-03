with Ada.Text_IO; use Ada.Text_IO;
with Math_Types;   use Math_Types;
with Proof_Target; use Proof_Target;

procedure Proof_Target_Demo is
   Result : constant Value := Run_Simd_Dot_Product_Demo;
begin
   Put_Line ("Kind = " & Result.Kind'Image);
   Put_Line ("N    = " & Result.N'Image);

   if Result.Kind = Scalar and then Result.N = 2 then
      Put_Line ("PASS: matches the Clojure-verified dot product (2.0)");
   else
      Put_Line ("FAIL");
   end if;
end Proof_Target_Demo;