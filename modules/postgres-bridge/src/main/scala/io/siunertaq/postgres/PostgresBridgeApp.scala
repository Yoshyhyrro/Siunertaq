package io.siunertaq.postgres

import cats.effect.{IO, IOApp}
import cats.effect.unsafe.IORuntime
import io.circe.parser.decode
import io.siunertaq.batch.DhallBatchRegistry
import org.apache.pekko.actor.ActorSystem
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.jdbc.datasource.embedded.{EmbeddedDatabaseBuilder, EmbeddedDatabaseType}
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager

import java.nio.file.{Files, Paths}

// ─── PostgresBridgeApp ───────────────────────────────────────────────────────
//
//  compileClass / compileFromClassFile / PushCompilation (ClassASTBridge)
//  had no caller anywhere in the source tree -- the rows/class_file_hash
//  wiring into ClickHouseSyncActor was correct but nothing ever triggered
//  it. This is that caller: loads PostgresBridgeJob.dhall (this module's
//  src/main/resources), and for each declared target runs
//  CompileClassTasklet through a Spring Batch step, mirroring
//  SiunertaqBatchApp's (modules/batch-bridge) Dhall-config -> Tasklet
//  bootstrap shape directly.
//
//  Deliberately does NOT go through JobSupervisorActor/StepExecutorActor
//  (modules/batch-bridge): StepExecutorActor.runSpringBatchStep hard-codes
//  StackMachineTasklet(stepDef.inputProg, ...) for every step, and
//  StepDef.inputProg is typed to the BSD Quiver stack machine's Program --
//  neither fits "compile this .class file" without either widening StepDef
//  with a discriminator (touches shared, tested code StackMachineTasklet's
//  existing callers already depend on) or forcing a compilation request
//  through Program/StackInstr's JSON shape, which it isn't. This costs the
//  JobSupervisorActor's COND-based step-skip logic and its Pekko
//  OneForOneStrategy ABEND handling between steps -- not missed yet, since
//  each target here is independent (compiling one .class file doesn't
//  depend on a previous target's result the way BatchJob.dhall's COND
//  steps can). If that stops being true, giving StepDef a discriminator
//  and folding this into JobSupervisorActor properly becomes worth the
//  shared-code risk; until then this stays a parallel, smaller path.
//
//  ClickHouseSyncActor itself still owns its own retry/supervision
//  (OneForOneStrategy, restart on IOException) independently of Spring
//  Batch's ExitStatus for this step -- a step reporting COMPLETED means
//  compileFromClassFile's IO succeeded and PushCompilation was sent, not
//  that ClickHouse itself has necessarily ingested it yet.
object PostgresBridgeApp extends IOApp.Simple:

  private def buildInfra: (JobRepository, PlatformTransactionManager) =
    val ds = EmbeddedDatabaseBuilder()
      .setType(EmbeddedDatabaseType.H2)
      .addScript("classpath:org/springframework/batch/core/schema-h2.sql")
      .build()
    val txMgr = DataSourceTransactionManager(ds)
    val factory = JobRepositoryFactoryBean()
    factory.setDataSource(ds)
    factory.setTransactionManager(txMgr)
    factory.afterPropertiesSet()
    (factory.getObject, txMgr)

  override def run: IO[Unit] =
    IO(ActorSystem("postgres-bridge"))
      .bracket(runJob)(sys => IO.fromFuture(IO(sys.terminate())).void)

  private def runJob(system: ActorSystem): IO[Unit] =
    val ioRuntime = IORuntime.global

    for
      dhallPath <- IO(Paths.get(
        sys.env.getOrElse(
          "POSTGRES_BRIDGE_JOB_DHALL",
          "modules/postgres-bridge/src/main/resources/PostgresBridgeJob.dhall"
        )
      ))
      dhallExpr <- IO.blocking(Files.readString(dhallPath))
      json      <- DhallBatchRegistry.evalDhall(dhallExpr)
      jobDef    <- IO.fromEither(
                     decode[PostgresBridgeJobDef](json)
                       .left.map(e => RuntimeException(s"PostgresBridgeJobDef decode failed: ${e.getMessage}"))
                   )

      clickHouseSync = system.actorOf(
        ClickHouseSyncActor.props(ClickHouseConfig().fromEnv, ioRuntime),
        name = "clickhouse-sync"
      )

      (jobRepository, txMgr) = buildInfra

      // Sequential, blocking, same as StepExecutorActor.runSpringBatchStep --
      // step.execute() is a blocking Spring Batch call either way, and
      // these targets don't depend on each other's outcome (see header).
      results <- IO.blocking {
        jobDef.targets.map { target =>
          val tasklet  = CompileClassTasklet(target, clickHouseSync, ioRuntime)
          val step     = StepBuilder(target.classFilePath, jobRepository)
                           .tasklet(tasklet, txMgr)
                           .build()
          val jobExec  = jobRepository.createJobExecution(jobDef.jobName, JobParameters())
          val stepExec = jobExec.createStepExecution(target.classFilePath)
          jobRepository.add(stepExec)
          step.execute(stepExec)
          (target.classFilePath, stepExec.getExitStatus.getExitCode)
        }
      }

      _ <- IO.println(s"=== ${jobDef.jobName}: ${results.size} target(s) ===")
      _ <- IO.blocking(results.foreach { case (path, status) => println(s"  $path -> $status") })
    yield ()