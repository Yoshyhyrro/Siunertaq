with Math_Types; use Math_Types;

package Math_Program with SPARK_Mode is

   --  Mirrors io.siunertaq.expr.Instr
   --  (modules/core/src/main/scala/io/siunertaq/expr/Program.scala):
   --
   --    enum Instr derives CanEqual:
   --      case PushScalar(n: Int)
   --      case PushVec3(x: Int, y: Int, z: Int)
   --      case AddScalar
   --      case AddVec3
   --      case MulScalar
   --      case DotVec3
   --
   type Instr_Kind is (Push_Scalar, Push_Vec3,
                        Add_Scalar, Add_Vec3,
                        Mul_Scalar, Dot_Vec3);

   type Instr (Kind : Instr_Kind := Push_Scalar) is record
      case Kind is
         when Push_Scalar =>
            Scalar_Arg : Integer;
         when Push_Vec3 =>
            Vec_X : Integer;
            Vec_Y : Integer;
            Vec_Z : Integer;
         when Add_Scalar | Add_Vec3 | Mul_Scalar | Dot_Vec3 =>
            null;
      end case;
   end record;

   --  Mirrors io.siunertaq.expr.Stack.requiredDepth (Program.scala).
   --  Written as a `case` expression, so -- unlike evaluate-cond's Clojure
   --  `(case op ...)` with no default clause -- GNAT itself refuses to
   --  compile this if a new Instr_Kind is ever added and left unhandled
   --  here. No SPARK/gnatprove run is even needed for that guarantee; it
   --  is plain Ada legality.
   function Required_Depth (Kind : Instr_Kind) return Natural is
     (case Kind is
        when Push_Scalar | Push_Vec3                       => 0,
        when Add_Scalar | Add_Vec3 | Mul_Scalar | Dot_Vec3  => 2);

   --  Mirrors io.siunertaq.expr.Stack.depthDelta (Program.scala).
   function Depth_Delta (Kind : Instr_Kind) return Integer is
     (case Kind is
        when Push_Scalar | Push_Vec3                       => 1,
        when Add_Scalar | Add_Vec3 | Mul_Scalar | Dot_Vec3  => -1);

   --  Bounded stack. Program.scala's MachineStack = List[Value] is
   --  unbounded; a fixed-capacity array keeps overflow/underflow
   --  decidable for gnatprove rather than relying on GC/unbounded
   --  allocation the way the JVM tree-walker does.
   Max_Stack_Depth : constant := 64;
   subtype Stack_Count is Natural range 0 .. Max_Stack_Depth;

   type Value_Array is array (1 .. Max_Stack_Depth) of Value;

   type Machine_Stack is record
      Top    : Stack_Count := 0;
      Values : Value_Array;
   end record;

   Empty_Stack : constant Machine_Stack :=
     (Top => 0, Values => (others => Make_Scalar (0)));

   --  Mirrors io.siunertaq.expr.ProgramEval.execOne (Lowering.scala).
   --
   --  The precondition is the SPARK-native counterpart of what execOne
   --  checks *at run time* via pattern-match fallthrough
   --  (`case _ => Left("... stack underflow or type error")`): here,
   --  both the required depth AND the required Kind of each operand are
   --  proof obligations discharged at every call site instead. This is
   --  the same "well-typed programs can't go wrong" guarantee that
   --  Lowering.lower enforces dynamically via ExprTyping.isWellTyped --
   --  restated as a static contract.
   function Exec_One (I : Instr; S : Machine_Stack) return Machine_Stack
     with
       Pre  => S.Top >= Required_Depth (I.Kind)
               and then S.Top + Depth_Delta (I.Kind) in 0 .. Max_Stack_Depth
               and then
                 (case I.Kind is
                    when Push_Scalar | Push_Vec3 =>
                      True,
                    when Add_Scalar | Mul_Scalar =>
                      S.Values (S.Top).Kind = Scalar
                      and then S.Values (S.Top - 1).Kind = Scalar,
                    when Add_Vec3 | Dot_Vec3 =>
                      S.Values (S.Top).Kind = Vec3
                      and then S.Values (S.Top - 1).Kind = Vec3),
       Post => Exec_One'Result.Top = S.Top + Depth_Delta (I.Kind);

   --  Mirrors io.siunertaq.expr.ProgramEval.exec (Lowering.scala).
   --
   --  SCOPE NOTE: this executes P against Exec_One instruction by
   --  instruction; it does not yet separately re-derive
   --  ExprTyping.isWellTyped for the flat Instr_Array form (that
   --  corresponds to Lowering.lower's guard, not lowerUnchecked's body).
   --  Concretely: Exec_Program only carries P'Length > 0 as a
   --  precondition today, so gnatprove cannot yet discharge Exec_One's
   --  stack-shape precondition automatically for an arbitrary P -- only
   --  for P's actually produced by a well-typed lowering, which is the
   --  next milestone, not this one.
   type Instr_Array is array (Positive range <>) of Instr;

   function Exec_Program (P : Instr_Array) return Value
     with Pre => P'Length > 0;

end Math_Program;