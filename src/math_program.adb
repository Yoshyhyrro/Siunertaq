package body Math_Program is

   function Exec_One (I : Instr; S : Machine_Stack) return Machine_Stack is
      Result : Machine_Stack := S;
   begin
      case I.Kind is
         when Push_Scalar =>
            Result.Top := Result.Top + 1;
            Result.Values (Result.Top) := Make_Scalar (I.Scalar_Arg);

         when Push_Vec3 =>
            Result.Top := Result.Top + 1;
            Result.Values (Result.Top) :=
              Make_Vec3 (I.Vec_X, I.Vec_Y, I.Vec_Z);

         when Add_Scalar =>
            declare
               B : constant Value := Result.Values (Result.Top);
               A : constant Value := Result.Values (Result.Top - 1);
            begin
               Result.Top := Result.Top - 1;
               Result.Values (Result.Top) := Make_Scalar (A.N + B.N);
            end;

         when Add_Vec3 =>
            declare
               B : constant Value := Result.Values (Result.Top);
               A : constant Value := Result.Values (Result.Top - 1);
            begin
               Result.Top := Result.Top - 1;
               Result.Values (Result.Top) :=
                 Make_Vec3 (A.X + B.X, A.Y + B.Y, A.Z + B.Z);
            end;

         when Mul_Scalar =>
            declare
               B : constant Value := Result.Values (Result.Top);
               A : constant Value := Result.Values (Result.Top - 1);
            begin
               Result.Top := Result.Top - 1;
               Result.Values (Result.Top) := Make_Scalar (A.N * B.N);
            end;

         when Dot_Vec3 =>
            declare
               B : constant Value := Result.Values (Result.Top);
               A : constant Value := Result.Values (Result.Top - 1);
            begin
               Result.Top := Result.Top - 1;
               Result.Values (Result.Top) :=
                 Make_Scalar (A.X * B.X + A.Y * B.Y + A.Z * B.Z);
            end;
      end case;

      return Result;
   end Exec_One;

   function Exec_Program (P : Instr_Array) return Value is
      S : Machine_Stack := Empty_Stack;
   begin
      for Idx in P'Range loop
         pragma Loop_Invariant (S.Top <= Max_Stack_Depth);
         S := Exec_One (P (Idx), S);
      end loop;
      return S.Values (S.Top);
   end Exec_Program;

end Math_Program;