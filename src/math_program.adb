--  =============================================================================
--  math_program.adb
--
--  Implementation of SPARK-mode machine execution loop and instruction interpreter.
--  =============================================================================

package body Math_Program is

   function Exec_One (I : Instr; S : Machine_Stack) return Machine_Stack is
      New_Stack : Machine_Stack := S;
   begin
      case I.Kind is
         when Push_Scalar =>
            New_Stack.Top := S.Top + 1;
            New_Stack.Values (New_Stack.Top) := Make_Scalar (I.Scalar_Arg);

         when Push_Vec3 =>
            New_Stack.Top := S.Top + 1;
            New_Stack.Values (New_Stack.Top) := Make_Vec3 (I.Vec_X, I.Vec_Y, I.Vec_Z);

         when Add_Scalar =>
            declare
               Op2 : constant Integer := S.Values (S.Top).N;
               Op1 : constant Integer := S.Values (S.Top - 1).N;
            begin
               New_Stack.Top := S.Top - 1;
               New_Stack.Values (New_Stack.Top) := Make_Scalar (Op1 + Op2);
            end;

         when Add_Vec3 =>
            declare
               V2 : constant Value := S.Values (S.Top);
               V1 : constant Value := S.Values (S.Top - 1);
            begin
               New_Stack.Top := S.Top - 1;
               New_Stack.Values (New_Stack.Top) :=
                 Make_Vec3 (V1.X + V2.X, V1.Y + V2.Y, V1.Z + V2.Z);
            end;

         when Mul_Scalar =>
            declare
               Op2 : constant Integer := S.Values (S.Top).N;
               Op1 : constant Integer := S.Values (S.Top - 1).N;
            begin
               New_Stack.Top := S.Top - 1;
               New_Stack.Values (New_Stack.Top) := Make_Scalar (Op1 * Op2);
            end;

         when Dot_Vec3 =>
            declare
               V2 : constant Value := S.Values (S.Top);
               V1 : constant Value := S.Values (S.Top - 1);
               Dot : constant Integer := (V1.X * V2.X) + (V1.Y * V2.Y) + (V1.Z * V2.Z);
            begin
               New_Stack.Top := S.Top - 1;
               New_Stack.Values (New_Stack.Top) := Make_Scalar (Dot);
            end;
      end case;

      return New_Stack;
   end Exec_One;

   function Exec_Program (P : Instr_Array) return Value is
      St : Machine_Stack := Empty_Stack;
   begin
      for Index in P'Range loop
         St := Exec_One (P (Index), St);
      end loop;
      return St.Values (St.Top);
   end Exec_Program;

end Math_Program;