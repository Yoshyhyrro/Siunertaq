name := "batch-bridge"

libraryDependencies ++= Seq(
  "org.apache.pekko"       %% "pekko-actor"          % "1.1.3",
  "org.springframework.batch" % "spring-batch-core"  % "5.1.2",
  "org.springframework"    %  "spring-jdbc"           % "6.1.14",
  "com.h2database"         %  "h2"                    % "2.2.224",  // in-memory JobRepository
)

lazy val batchBridge = project
  .in(file("modules/batch-bridge"))
  .dependsOn(
    core,         // expr.ProgramEval, expr.Instr を使う
    dhallBridge   // BatchJobDef, DhallBatchRegistry, CondEvaluator を使う
  )