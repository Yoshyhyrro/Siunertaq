with Ada.Exceptions;
with Ada.Text_IO;   use Ada.Text_IO;
with Math_Types;    use Math_Types;
with Math_Program;  use Math_Program;

procedure Contract_Violation_Demo is
   --  Deliberately ill-typed: pushes a Scalar then a Vec3, then asks
   --  for Add_Scalar (which demands two Scalars on top). This is the
   --  Math_Program analogue of the Clojure {:Compare {:threshold 0
   --  :op "LTE"}} counterexample: a shape Exec_One's precondition is
   --  specifically designed to rule out.
   Bad_Program : constant Instr_Array :=
     [1 => (Kind => Push_Scalar, Scalar_Arg => 5),
      2 => (Kind => Push_Vec3, Vec_X => 1, Vec_Y => 2, Vec_Z => 3),
      3 => (Kind => Add_Scalar)];

   Result : Value;
begin
   Result := Exec_Program (Bad_Program);
   Put_Line ("UNEXPECTED: no contract violation, got N=" & Result.N'Image);
exception
   when Error : others =>
      Put_Line ("Caught (expected): " & Ada.Exceptions.Exception_Message (Error));
end Contract_Violation_Demo;