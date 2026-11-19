package body Math_Types is

   function Make_Scalar (N : Integer) return Value is
   begin
      return (Kind => Scalar, N => N);
   end Make_Scalar;

   function Make_Vec3 (X, Y, Z : Integer) return Value is
   begin
      return (Kind => Vec3, X => X, Y => Y, Z => Z);
   end Make_Vec3;

end Math_Types;