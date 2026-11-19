package Math_Types with SPARK_Mode is

   --  Mirrors io.siunertaq.expr.Ty
   --  (modules/core/src/main/scala/io/siunertaq/expr/Expr.scala):
   --
   --    enum Ty derives CanEqual:
   --      case Scalar
   --      case Vec3
   --
   type Ty is (Scalar, Vec3);

   --  Mirrors io.siunertaq.expr.Value / ScalarValue / Vec3Value
   --  (same file). Ada's discriminated record is the closed-sum-type
   --  idiom corresponding to Scala's `sealed trait Value` plus two
   --  `final case class` variants: the discriminant `Kind` plays the
   --  role of the sealed hierarchy's tag, and the variant part
   --  statically forbids ever reading X/Y/Z on a Scalar value, or N on
   --  a Vec3 value -- there is no representable ill-formed Value.
   type Value (Kind : Ty := Scalar) is record
      case Kind is
         when Scalar =>
            N : Integer;
         when Vec3 =>
            X : Integer;
            Y : Integer;
            Z : Integer;
      end case;
   end record;

   --  Mirrors the ScalarValue(n) / Vec3Value(x, y, z) constructors.
   function Make_Scalar (N : Integer) return Value
     with Post => Make_Scalar'Result.Kind = Scalar
                  and then Make_Scalar'Result.N = N;

   function Make_Vec3 (X, Y, Z : Integer) return Value
     with Post => Make_Vec3'Result.Kind = Vec3
                  and then Make_Vec3'Result.X = X
                  and then Make_Vec3'Result.Y = Y
                  and then Make_Vec3'Result.Z = Z;

end Math_Types;