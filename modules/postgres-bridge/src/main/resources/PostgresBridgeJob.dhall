{-|  PostgresBridgeJob.dhall
     modules/postgres-bridge/src/main/resources/PostgresBridgeJob.dhall

     Declares which .class files ClassASTBridge.compileFromClassFile should
     compile and push to ClickHouseSyncActor -- the same role BatchJob.dhall
     (modules/dhall-bridge) plays for the BSD Quiver stack machine's steps,
     loaded the same way (DhallBatchRegistry.evalDhall shells out to
     dhall-to-json; PostgresBridgeJobDef.scala's circe Decoder does the
     rest). Deliberately a separate file/type from BatchJob.dhall/StepDef:
     StepDef.input_prog is typed to the stack machine's Program, which a
     .class file's path isn't -- see PostgresBridgeApp.scala's header for
     why this doesn't reuse JobSupervisorActor/StepExecutorActor either.

     No union types here (unlike BatchJob.dhall's StackInstr/CondExpr),
     so this doesn't need BatchJob.dhall's Prelude.JSON workaround for
     empty-payload alternatives colliding under dhall-to-json's default
     encoding -- a plain record list serializes cleanly on its own.

     Usage:
       dhall-to-json --file PostgresBridgeJob.dhall
       # or: POSTGRES_BRIDGE_JOB_DHALL=/path/to/this.dhall sbt "postgresBridge/runMain io.siunertaq.postgres.PostgresBridgeApp"
-}

let ClassTarget =
      { class_file_path  : Text
      , target_method    : Text
      , target_descriptor : Optional Text
      , allow_stubs      : Bool
      }

let mkTarget
    : Text -> Text -> ClassTarget
    = \(path : Text) ->
      \(method : Text) ->
        { class_file_path = path
        , target_method = method
        , target_descriptor = None Text
        , allow_stubs = False
        }

-- Example targets. Replace with the real .class file(s) to compile, or
-- point POSTGRES_BRIDGE_JOB_DHALL at a different file entirely for a
-- different environment (dev / CI / prod) without touching Scala code,
-- the same way BATCH_JOB_DHALL works for SiunertaqBatchApp.
let targets =
      [ mkTarget
          "modules/postgres-bridge/target/scala-3.8.3/classes/io/siunertaq/postgres/example/Sample.class"
          "execute"
      ]

in    { job_name = "postgres-bridge-compile", targets }
    : { job_name : Text, targets : List ClassTarget }
