package Proof_Target with SPARK_Mode is
   procedure Dummy_Proof (X : in out Integer) with
     Pre  => X < Integer'Last,
     Post => X > X'Old;
end Proof_Target;
